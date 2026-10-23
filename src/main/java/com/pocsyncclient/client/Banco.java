package com.pocsyncclient.client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Banco SQLite local do "aparelho": visitas + fila de pendências (outbox). */
public class Banco implements AutoCloseable {

    /** Operação offline aguardando envio ao servidor. */
    public record Op(String opId, String tipo, String visitaId, String cooperado,
                     String talhao, String observacao, long baseVersao, String atualizadoEm) { }

    public record Visita(String id, String cooperado, String talhao, String observacao,
                         long versao, boolean excluido, boolean pendente) { }

    @FunctionalInterface
    public interface Acao {
        void executar() throws SQLException;
    }

    private static final String SELECT_VISITA =
            "SELECT v.id, v.cooperado, v.talhao, v.observacao, v.versao, v.excluido, "
          + "EXISTS(SELECT 1 FROM fila f WHERE f.visita_id = v.id) AS pendente FROM visita v";

    private final Connection conn;

    public Banco(String arquivo) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver SQLite não encontrado", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + arquivo);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS visita (
                  id TEXT PRIMARY KEY,
                  cooperado TEXT, talhao TEXT, observacao TEXT,
                  versao INTEGER NOT NULL DEFAULT 0,
                  atualizado_em TEXT,
                  excluido INTEGER NOT NULL DEFAULT 0)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS fila (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  op_id TEXT NOT NULL UNIQUE,
                  tipo TEXT NOT NULL,
                  visita_id TEXT NOT NULL,
                  cooperado TEXT, talhao TEXT, observacao TEXT,
                  base_versao INTEGER NOT NULL,
                  atualizado_em TEXT NOT NULL)""");
            st.execute("CREATE TABLE IF NOT EXISTS meta (chave TEXT PRIMARY KEY, valor TEXT)");
        }
    }

    // ---------- Transação ----------

    /** Executa a ação de forma atômica: ou tudo é gravado, ou nada (importante se o app for fechado à força). */
    public void transacao(Acao acao) throws SQLException {
        conn.setAutoCommit(false);
        try {
            acao.executar();
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ---------- Operações feitas pelo usuário (funcionam sem internet) ----------

    public String criar(String cooperado, String talhao, String obs) throws SQLException {
        String id = UUID.randomUUID().toString();
        String agora = Instant.now().toString();
        transacao(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO visita (id, cooperado, talhao, observacao, versao, atualizado_em, excluido) "
                  + "VALUES (?,?,?,?,0,?,0)")) {
                ps.setString(1, id);
                ps.setString(2, cooperado);
                ps.setString(3, talhao);
                ps.setString(4, obs);
                ps.setString(5, agora);
                ps.executeUpdate();
            }
            enfileirar("CRIAR", id, cooperado, talhao, obs, 0, agora);
        });
        return id;
    }

    public void editar(String id, String novaObs) throws SQLException {
        Visita v = buscar(id);
        if (v == null || v.excluido()) {
            throw new IllegalArgumentException("Visita não encontrada ou já excluída: " + id);
        }
        // Versão que o servidor terá quando as operações pendentes dessa visita forem aplicadas.
        long base = v.versao() + contarPendentesDe(id);
        String agora = Instant.now().toString();
        transacao(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE visita SET observacao = ?, atualizado_em = ? WHERE id = ?")) {
                ps.setString(1, novaObs);
                ps.setString(2, agora);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            enfileirar("EDITAR", id, v.cooperado(), v.talhao(), novaObs, base, agora);
        });
    }

    public void excluir(String id) throws SQLException {
        Visita v = buscar(id);
        if (v == null || v.excluido()) {
            throw new IllegalArgumentException("Visita não encontrada ou já excluída: " + id);
        }
        long base = v.versao() + contarPendentesDe(id);
        String agora = Instant.now().toString();
        transacao(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE visita SET excluido = 1, atualizado_em = ? WHERE id = ?")) {
                ps.setString(1, agora);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            enfileirar("EXCLUIR", id, v.cooperado(), v.talhao(), v.observacao(), base, agora);
        });
    }

    private void enfileirar(String tipo, String visitaId, String cooperado, String talhao,
                            String obs, long baseVersao, String quando) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fila (op_id, tipo, visita_id, cooperado, talhao, observacao, base_versao, atualizado_em) "
              + "VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, tipo);
            ps.setString(3, visitaId);
            ps.setString(4, cooperado);
            ps.setString(5, talhao);
            ps.setString(6, obs);
            ps.setLong(7, baseVersao);
            ps.setString(8, quando);
            ps.executeUpdate();
        }
    }

    // ---------- Consultas locais ----------

    public Visita buscar(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_VISITA + " WHERE v.id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapear(rs) : null;
            }
        }
    }

    public List<Visita> todas() throws SQLException {
        List<Visita> lista = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SELECT_VISITA + " ORDER BY v.rowid")) {
            while (rs.next()) {
                lista.add(mapear(rs));
            }
        }
        return lista;
    }

    /** Permite digitar só o começo do UUID nos comandos. */
    public String resolverId(String prefixo) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM visita WHERE id LIKE ? LIMIT 2")) {
            ps.setString(1, prefixo + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        if (ids.size() == 1) {
            return ids.get(0);
        }
        throw new IllegalArgumentException(ids.isEmpty()
                ? "Nenhuma visita com esse id: " + prefixo
                : "Id ambíguo, digite mais caracteres: " + prefixo);
    }

    private Visita mapear(ResultSet rs) throws SQLException {
        return new Visita(rs.getString("id"), rs.getString("cooperado"), rs.getString("talhao"),
                rs.getString("observacao"), rs.getLong("versao"),
                rs.getInt("excluido") == 1, rs.getInt("pendente") == 1);
    }

    // ---------- Fila de pendências ----------

    public List<Op> operacoesPendentes(int limite) throws SQLException {
        List<Op> lista = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT op_id, tipo, visita_id, cooperado, talhao, observacao, base_versao, atualizado_em "
              + "FROM fila ORDER BY seq LIMIT ?")) {
            ps.setInt(1, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Op(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8)));
                }
            }
        }
        return lista;
    }

    public void removerOperacoes(Collection<String> opIds) throws SQLException {
        transacao(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM fila WHERE op_id = ?")) {
                for (String opId : opIds) {
                    ps.setString(1, opId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public int contarPendentes() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fila")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int contarPendentesDe(String visitaId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM fila WHERE visita_id = ?")) {
            ps.setString(1, visitaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ---------- Dados vindos do servidor ----------

    /**
     * Grava no banco local o registro recebido do servidor.
     * Se a visita ainda tem operações pendentes, não sobrescreve (retorna false) para não perder a edição local.
     */
    public boolean aplicarDoServidor(String id, String cooperado, String talhao, String obs,
                                     long versao, String atualizadoEm, boolean excluido) throws SQLException {
        if (contarPendentesDe(id) > 0) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO visita (id, cooperado, talhao, observacao, versao, atualizado_em, excluido)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  cooperado = excluded.cooperado, talhao = excluded.talhao,
                  observacao = excluded.observacao, versao = excluded.versao,
                  atualizado_em = excluded.atualizado_em, excluido = excluded.excluido""")) {
            ps.setString(1, id);
            ps.setString(2, cooperado);
            ps.setString(3, talhao);
            ps.setString(4, obs);
            ps.setLong(5, versao);
            ps.setString(6, atualizadoEm);
            ps.setInt(7, excluido ? 1 : 0);
            ps.executeUpdate();
        }
        return true;
    }

    public long ultimaSeq() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT valor FROM meta WHERE chave = 'ultima_seq'")) {
            return rs.next() ? Long.parseLong(rs.getString(1)) : 0L;
        }
    }

    public void salvarUltimaSeq(long seq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (chave, valor) VALUES ('ultima_seq', ?)")) {
            ps.setString(1, Long.toString(seq));
            ps.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
