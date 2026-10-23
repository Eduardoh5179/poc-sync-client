package com.pocsyncclient.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Lógica de sincronização: envia a fila de pendências (push) e baixa as mudanças do servidor (pull). */
public class Sincronizador {

    private static final int TAMANHO_LOTE = 100;

    private final Banco banco;
    private final Api api;
    private final String dispositivo;

    public Sincronizador(Banco banco, Api api, String dispositivo) {
        this.banco = banco;
        this.api = api;
        this.dispositivo = dispositivo;
    }

    /**
     * @param perderResposta se true, simula a queda de conexão DEPOIS de o servidor processar o envio:
     *                       a resposta é descartada e a fila é mantida (teste de idempotência).
     */
    public void sincronizar(boolean perderResposta) throws Exception {
        long inicio = System.currentTimeMillis();
        Map<String, Integer> porStatus = new TreeMap<>();
        int enviadas = 0;

        // 1) PUSH: envia a fila em lotes; só remove da fila o que o servidor confirmou
        while (true) {
            List<Banco.Op> lote = banco.operacoesPendentes(TAMANHO_LOTE);
            if (lote.isEmpty()) {
                break;
            }
            JsonNode resp = api.push(dispositivo, lote);
            if (perderResposta) {
                System.out.println("[SIMULAÇÃO] O servidor recebeu o envio, mas a resposta foi 'perdida'.");
                System.out.println("A fila foi mantida (" + banco.contarPendentes() + " pendências). Rode 'sync' de novo.");
                return;
            }
            JsonNode resultados = resp.get("resultados");
            if (resultados == null || resultados.size() == 0) {
                throw new IllegalStateException("O servidor não confirmou nenhuma operação.");
            }
            List<String> confirmadas = new ArrayList<>();
            for (JsonNode r : resultados) {
                porStatus.merge(texto(r, "status"), 1, Integer::sum);
                confirmadas.add(texto(r, "opId"));
            }
            banco.removerOperacoes(confirmadas);
            enviadas += confirmadas.size();
        }

        // 2) PULL: baixa o que mudou no servidor desde a última sincronização
        JsonNode pull = api.pull(banco.ultimaSeq());
        JsonNode registros = pull.get("registros");
        int[] contagem = new int[2]; // [0] aplicados, [1] pulados por terem pendência local
        banco.transacao(() -> {
            for (JsonNode r : registros) {
                boolean ok = banco.aplicarDoServidor(
                        texto(r, "id"), texto(r, "cooperado"), texto(r, "talhao"), texto(r, "observacao"),
                        r.get("versao").asLong(), texto(r, "atualizadoEm"), r.get("excluido").asBoolean());
                contagem[ok ? 0 : 1]++;
            }
        });
        if (contagem[1] == 0) {
            banco.salvarUltimaSeq(pull.get("ultimaSeq").asLong());
        } else {
            System.out.println("Aviso: " + contagem[1] + " registros não foram atualizados por terem edição local pendente.");
        }

        long ms = System.currentTimeMillis() - inicio;
        System.out.println("Sincronização concluída em " + ms + " ms.");
        System.out.println("  Operações enviadas: " + enviadas + " " + porStatus);
        System.out.println("  Registros recebidos e aplicados: " + contagem[0]);
        System.out.println("  Pendências restantes: " + banco.contarPendentes());
    }

    /** Compara o banco local com o servidor e aponta registros ausentes ou divergentes. */
    public void verificar() throws Exception {
        int pendentes = banco.contarPendentes();
        if (pendentes > 0) {
            System.out.println("Atenção: há " + pendentes + " operações pendentes; diferenças são esperadas até sincronizar.");
        }
        Map<String, JsonNode> servidor = new HashMap<>();
        for (JsonNode r : api.pull(0).get("registros")) {
            servidor.put(texto(r, "id"), r);
        }

        List<Banco.Visita> locais = banco.todas();
        Set<String> idsLocais = new HashSet<>();
        int ausentes = 0;
        int diferentes = 0;
        for (Banco.Visita v : locais) {
            idsLocais.add(v.id());
            JsonNode s = servidor.get(v.id());
            if (s == null) {
                ausentes++;
                continue;
            }
            boolean igual = Objects.toString(v.cooperado(), "").equals(texto(s, "cooperado"))
                    && Objects.toString(v.talhao(), "").equals(texto(s, "talhao"))
                    && Objects.toString(v.observacao(), "").equals(texto(s, "observacao"))
                    && v.excluido() == s.get("excluido").asBoolean()
                    && v.versao() == s.get("versao").asLong();
            if (!igual) {
                diferentes++;
            }
        }
        int soNoServidor = 0;
        for (String id : servidor.keySet()) {
            if (!idsLocais.contains(id)) {
                soNoServidor++;
            }
        }

        System.out.println("Registros no aparelho:             " + locais.size());
        System.out.println("Registros no servidor:             " + servidor.size());
        System.out.println("Ausentes no servidor (perdidos):   " + ausentes);
        System.out.println("Com conteúdo/versão divergente:    " + diferentes);
        System.out.println("Só no servidor (de outros aparelhos ou ainda não baixados): " + soNoServidor);
        if (ausentes == 0 && diferentes == 0) {
            System.out.println("RESULTADO: nenhum registro local perdido ou divergente.");
        } else {
            System.out.println("RESULTADO: há diferenças. Sincronize e verifique de novo.");
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return (v == null || v.isNull()) ? "" : v.asText();
    }
}
