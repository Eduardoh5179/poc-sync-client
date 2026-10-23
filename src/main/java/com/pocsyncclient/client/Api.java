package com.pocsyncclient.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cliente HTTP do servidor central (endpoints /sync/push e /sync/pull). */
public class Api {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String base;

    public Api(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public JsonNode push(String dispositivo, List<Banco.Op> ops) throws IOException, InterruptedException {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Banco.Op op : ops) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opId", op.opId());
            m.put("tipo", op.tipo());
            m.put("id", op.visitaId());
            m.put("cooperado", op.cooperado());
            m.put("talhao", op.talhao());
            m.put("observacao", op.observacao());
            m.put("baseVersao", op.baseVersao());
            m.put("atualizadoEm", op.atualizadoEm());
            lista.add(m);
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("dispositivoId", dispositivo);
        corpo.put("operacoes", lista);

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/sync/push"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        return enviar(req);
    }

    public JsonNode pull(long desdeSeq) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/sync/pull?desde=" + desdeSeq))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        return enviar(req);
    }

    private JsonNode enviar(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            String corpo = resp.body() == null ? "" : resp.body();
            throw new IOException("Servidor respondeu HTTP " + resp.statusCode()
                    + (corpo.length() > 200 ? ": " + corpo.substring(0, 200) : ": " + corpo));
        }
        return json.readTree(resp.body());
    }
}
