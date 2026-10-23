package com.pocsyncclient.client;

import java.util.List;

/** Linha de comando do "aparelho" da PoC. Cada aparelho tem seu próprio arquivo SQLite. */
public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            uso();
            System.exit(1);
        }
        String dispositivo = args[0];
        String comando = args[1];

        try (Banco banco = new Banco("aparelho-" + dispositivo + ".db")) {
            switch (comando) {
                case "criar" -> {
                    String cooperado = arg(args, 2, "Cooperado");
                    String talhao = arg(args, 3, "T1");
                    String obs = arg(args, 4, "");
                    String id = banco.criar(cooperado, talhao, obs);
                    System.out.println("Visita criada offline. id=" + id.substring(0, 8) + " (completo: " + id + ")");
                }
                case "editar" -> {
                    String id = banco.resolverId(arg(args, 2, ""));
                    banco.editar(id, arg(args, 3, ""));
                    System.out.println("Visita editada offline: " + id.substring(0, 8));
                }
                case "excluir" -> {
                    String id = banco.resolverId(arg(args, 2, ""));
                    banco.excluir(id);
                    System.out.println("Visita marcada como excluída offline: " + id.substring(0, 8));
                }
                case "listar" -> listar(banco);
                case "pendencias" -> System.out.println("Operações na fila de pendências: " + banco.contarPendentes());
                case "gerar" -> gerar(banco, Integer.parseInt(arg(args, 2, "100")));
                case "sync" -> {
                    Api api = new Api(url(args));
                    try {
                        new Sincronizador(banco, api, dispositivo).sincronizar(tem(args, "--perder-resposta"));
                    } catch (Exception e) {
                        System.out.println("Falha na sincronização: " + descrever(e));
                        System.out.println("Nada foi perdido: " + banco.contarPendentes()
                                + " operações continuam salvas localmente. Tente de novo quando houver conexão.");
                    }
                }
                case "verificar" -> new Sincronizador(banco, new Api(url(args)), dispositivo).verificar();
                default -> {
                    System.out.println("Comando desconhecido: " + comando);
                    uso();
                }
            }
        } catch (Exception e) {
            System.err.println("Erro: " + descrever(e));
            System.exit(1);
        }
    }

    private static void listar(Banco banco) throws Exception {
        List<Banco.Visita> visitas = banco.todas();
        System.out.printf("%-9s %-14s %-7s %-30s %3s %-5s %-5s%n",
                "ID", "COOPERADO", "TALHAO", "OBSERVACAO", "V", "PEND", "EXCL");
        for (Banco.Visita v : visitas) {
            System.out.printf("%-9s %-14s %-7s %-30s %3d %-5s %-5s%n",
                    v.id().substring(0, 8), cortar(v.cooperado(), 14), cortar(v.talhao(), 7),
                    cortar(v.observacao(), 30), v.versao(),
                    v.pendente() ? "sim" : "nao", v.excluido() ? "sim" : "nao");
        }
        System.out.println("Total: " + visitas.size() + " registros | Pendências na fila: " + banco.contarPendentes());
    }

    private static void gerar(Banco banco, int quantidade) throws Exception {
        long inicio = System.nanoTime();
        for (int i = 1; i <= quantidade; i++) {
            banco.criar("Cooperado " + i, "T" + (i % 10), "Visita gerada numero " + i);
        }
        double ms = (System.nanoTime() - inicio) / 1_000_000.0;
        System.out.printf("%d visitas criadas offline em %.0f ms (média de %.2f ms por gravação).%n",
                quantidade, ms, ms / quantidade);
    }

    private static String arg(String[] args, int posicao, String padrao) {
        return args.length > posicao && !args[posicao].startsWith("--") ? args[posicao] : padrao;
    }

    private static String url(String[] args) {
        for (String a : args) {
            if (a.startsWith("--url=")) {
                return a.substring("--url=".length());
            }
        }
        return "http://localhost:8080";
    }

    private static boolean tem(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String cortar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }

    private static String descrever(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private static void uso() {
        System.out.println("""
            Uso: java -jar poc-sync-cliente-1.0.jar <aparelho> <comando> [argumentos]

            Comandos (funcionam sem internet):
              criar <cooperado> <talhao> <observacao>   cria uma visita
              editar <id> <nova observacao>             edita (basta o começo do id)
              excluir <id>                              exclui (exclusão lógica)
              listar                                    mostra os dados locais
              pendencias                                conta as operações na fila
              gerar <n>                                 cria n visitas (teste de volume)

            Comandos que usam o servidor:
              sync [--url=http://localhost:8080] [--perder-resposta]
              verificar [--url=http://localhost:8080]
            """);
    }
}
