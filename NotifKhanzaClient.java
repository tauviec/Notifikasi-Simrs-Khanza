import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * Notifikasi Khanza - Klien Desktop (System Tray)
 *
 * Connect langsung (read-only) ke database SIMRS Khanza, memunculkan
 * notifikasi + alarm di system tray saat ada permintaan Lab/Radiologi/Resep
 * baru yang belum selesai. Berdiri sendiri, tidak tergantung aplikasi web.
 *
 * Catatan penting: Khanza (aplikasi Java desktop) tidak punya mekanisme
 * apa pun untuk dibuka langsung ke record tertentu dari luar (sudah
 * ditelusuri di source-nya - tidak ada command-line arg, socket, atau
 * URL protocol yang didengarkan). Karena itu, klik notifikasi di sini
 * membuka jendela detail milik program ini sendiri (bukan membuka Khanza),
 * lengkap dengan tombol salin No. Rawat supaya gampang dicari manual di Khanza.
 */
public class NotifKhanzaClient {

    static Properties config = new Properties();
    static Set<String> seenIds = Collections.synchronizedSet(new HashSet<>());
    static final Map<String, Long> lastAlarmedAt = new ConcurrentHashMap<>();
    static final List<PendingItem> pendingToShow = new CopyOnWriteArrayList<>();
    static TrayIcon trayIcon;
    static JDialog listDialog;
    static DefaultTableModel tableModel;
    static boolean firstRun;

    public static void main(String[] args) throws Exception {
        loadConfig();
        loadSeenIds();

        if (!SystemTray.isSupported()) {
            JOptionPane.showMessageDialog(null, "System Tray tidak didukung di komputer ini.", "Notifikasi Khanza", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Class.forName("com.mysql.jdbc.Driver");

        Platform.startup(() -> {});
        Platform.setImplicitExit(false);

        setupTrayIcon();

        int intervalSec = Integer.parseInt(config.getProperty("poll.interval.seconds", "60"));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(NotifKhanzaClient::pollSekali, 2, intervalSec, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveSeenIds()));
    }

    // ------------------------------------------------------------------
    // Config & dedup lokal
    // ------------------------------------------------------------------

    static void loadConfig() throws IOException {
        File f = new File("config.properties");
        if (!f.exists()) {
            JOptionPane.showMessageDialog(null,
                "config.properties tidak ditemukan di folder ini.\nSalin config.properties.example dan isi kredensial database.",
                "Notifikasi Khanza", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        try (InputStream in = new FileInputStream(f)) {
            config.load(in);
        }
    }

    static Path seenIdsPath() {
        return Paths.get("seen_ids.txt");
    }

    static void loadSeenIds() {
        Path p = seenIdsPath();
        if (!Files.exists(p)) {
            // Instalasi pertama kali: jangan banjiri notifikasi untuk ratusan item lama
            // yang sudah pending sebelum program ini dipasang. Poll pertama akan mencatat
            // semuanya sebagai "sudah dilihat" secara diam-diam (lihat pollSekali()).
            firstRun = true;
            return;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) seenIds.add(line.trim());
            }
        } catch (IOException e) {
            System.err.println("Gagal membaca seen_ids.txt: " + e.getMessage());
        }
    }

    static synchronized void appendSeenId(String key) {
        seenIds.add(key);
        try (BufferedWriter w = Files.newBufferedWriter(seenIdsPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(key);
            w.newLine();
        } catch (IOException e) {
            System.err.println("Gagal menyimpan seen_ids.txt: " + e.getMessage());
        }
    }

    static void saveSeenIds() {
        // seen_ids ditulis incremental lewat appendSeenId(); tidak ada yang perlu di-flush di sini,
        // hook ini disediakan untuk pengembangan lebih lanjut kalau nanti butuh simpan state lain.
    }

    // ------------------------------------------------------------------
    // System tray
    // ------------------------------------------------------------------

    static void setupTrayIcon() throws AWTException {
        SystemTray tray = SystemTray.getSystemTray();
        Image icon = buatIkonTray();

        PopupMenu menu = new PopupMenu();
        MenuItem cekSekarang = new MenuItem("Cek Sekarang");
        cekSekarang.addActionListener(e -> new Thread(NotifKhanzaClient::pollSekali).start());
        MenuItem keluar = new MenuItem("Keluar");
        keluar.addActionListener(e -> System.exit(0));
        menu.add(cekSekarang);
        menu.addSeparator();
        menu.add(keluar);

        trayIcon = new TrayIcon(icon, "Notifikasi Khanza", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> tampilkanDaftarPending());
        tray.add(trayIcon);
    }

    static Image buatIkonTray() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x0E, 0x6B, 0x57));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        String label = "N";
        int tx = (size - fm.stringWidth(label)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(label, tx, ty);
        g.dispose();
        return img;
    }

    static void mainkanAlarm() {
        int jumlahPutaran;
        try {
            jumlahPutaran = Integer.parseInt(config.getProperty("alarm.sound.repeat", "3").trim());
        } catch (NumberFormatException e) {
            jumlahPutaran = 3;
        }
        if (jumlahPutaran < 1) jumlahPutaran = 1;

        String soundFile = config.getProperty("alarm.sound.file", "").trim();
        if (!soundFile.isEmpty()) {
            File f = new File(soundFile);
            if (f.isFile()) {
                putarSuaraAlarm(f, jumlahPutaran);
                return;
            } else {
                System.err.println("[NotifKhanzaClient] alarm.sound.file tidak ditemukan: " + soundFile + " - pakai beep bawaan.");
            }
        }
        mainkanBeep(jumlahPutaran);
    }

    static void mainkanBeep(int jumlahPutaran) {
        Toolkit tk = Toolkit.getDefaultToolkit();
        int kali = jumlahPutaran;
        new Thread(() -> {
            try {
                for (int i = 0; i < kali; i++) {
                    tk.beep();
                    Thread.sleep(280);
                }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    static void putarSuaraAlarm(File f, int kaliUlang) {
        // Tiap putaran pakai MediaPlayer BARU yang terpisah (bukan satu player yang di-seek
        // atau setCycleCount) -- putaran tunggal (1x play sampai natural selesai) sudah
        // terbukti selalu utuh di pengujian. TAPI: player TIDAK di-dispose() langsung begitu
        // onEndOfMedia terpicu -- currentTime sudah mencapai durasi penuh saat event itu,
        // tapi belum tentu semua audionya sudah benar-benar keluar dari speaker (masih ada
        // buffer output yang mengalir). Semua player dibiarkan hidup sampai SELURUH
        // rangkaian putaran selesai, baru dibersihkan sekalian belakangan (dengan jeda) --
        // supaya tidak ada dispose() yang kepotong di tengah proses buffer masih mengalir,
        // baik di putaran tengah maupun putaran terakhir.
        putarSatuKali(f, kaliUlang, 1, new ArrayList<>());
    }

    static void putarSatuKali(File f, int totalPutaran, int putaranKe, List<MediaPlayer> semuaPlayer) {
        try {
            Media media = new Media(f.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);
            semuaPlayer.add(player);
            player.setOnError(() -> {
                System.err.println("[NotifKhanzaClient] Gagal memutar file suara alarm: " + player.getError());
                mainkanBeep(totalPutaran - putaranKe + 1);
                bersihkanSemuaPlayer(semuaPlayer);
            });
            player.setOnEndOfMedia(() -> {
                PauseTransition jeda = new PauseTransition(javafx.util.Duration.millis(500));
                if (putaranKe < totalPutaran) {
                    jeda.setOnFinished(ev -> putarSatuKali(f, totalPutaran, putaranKe + 1, semuaPlayer));
                } else {
                    jeda.setOnFinished(ev -> bersihkanSemuaPlayer(semuaPlayer));
                }
                jeda.play();
            });
            Platform.runLater(player::play);
        } catch (Exception e) {
            System.err.println("[NotifKhanzaClient] Gagal memutar file suara alarm: " + e.getMessage());
            mainkanBeep(totalPutaran - putaranKe + 1);
        }
    }

    static void bersihkanSemuaPlayer(List<MediaPlayer> semuaPlayer) {
        for (MediaPlayer p : semuaPlayer) {
            try { p.dispose(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // Polling & query database Khanza (read-only)
    // ------------------------------------------------------------------

    static void pollSekali() {
        try (Connection conn = bukaKoneksi()) {
            List<String> jenisList = Arrays.asList(config.getProperty("jenis.watch", "lab,radiologi,farmasi").split(","));

            List<PendingItem> semua = new ArrayList<>();
            if (jenisList.contains("lab")) {
                semua.addAll(queryPendingLab(conn));
            }
            if (jenisList.contains("radiologi")) {
                semua.addAll(queryPending(conn, "permintaan_radiologi", "noorder", "tgl_hasil", "'0000-00-00'",
                        "tgl_permintaan", "jam_permintaan", "radiologi", null));
            }
            if (jenisList.contains("farmasi")) {
                semua.addAll(queryPending(conn, "resep_obat", "no_resep", "tgl_perawatan", "'0000-00-00'",
                        "tgl_peresepan", "jam_peresepan", "farmasi", null));
            }

            // Cuma proses permintaan bertanggal HARI INI -- backlog lama (data lama Khanza
            // yang entah kenapa belum pernah diisi hasilnya sampai bertahun-tahun) sengaja
            // tidak ikut memicu alarm/pengingat maupun muncul di daftar, supaya notifikasi
            // ini benar-benar fokus ke permintaan yang realtime/berjalan.
            String hariIni = new SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
            semua.removeIf(item -> !hariIni.equals(item.tanggal));

            // Resep Farmasi bisa disaring lagi per unit asal (Rawat Jalan / Rawat Inap) --
            // mis. PC di Farmasi Rawat Jalan cuma mau notif resep dari Ralan saja.
            // Tidak berlaku untuk Lab/Radiologi, cuma untuk jenis "farmasi".
            List<String> farmasiUnit = daftarDariConfig("farmasi.unit", "ralan,ranap");
            semua.removeIf(item -> "farmasi".equals(item.jenis) && item.statusLanjut != null
                && !farmasiUnit.contains(item.statusLanjut.toLowerCase()));

            // Untuk Ralan (Rawat Jalan) di Farmasi, bisa disaring lagi per poliklinik asal --
            // mis. farmasi.ralan.poli=IGD supaya notif Ralan cuma muncul untuk resep dari IGD.
            // Kosong (default) berarti semua poliklinik Ralan tetap dipantau, tidak disaring.
            List<String> farmasiRalanPoli = daftarDariConfig("farmasi.ralan.poli", "");
            if (!farmasiRalanPoli.isEmpty()) {
                semua.removeIf(item -> "farmasi".equals(item.jenis) && "Ralan".equals(item.statusLanjut)
                    && (item.asal == null || !farmasiRalanPoli.contains(item.asal.trim().toLowerCase())));
            }

            long repeatMinutes;
            try {
                repeatMinutes = Long.parseLong(config.getProperty("alarm.repeat.minutes", "2").trim());
            } catch (NumberFormatException e) {
                repeatMinutes = 2;
            }
            long repeatMillis = repeatMinutes > 0 ? repeatMinutes * 60_000L : 0;
            long now = System.currentTimeMillis();

            List<PendingItem> itemBaru = new ArrayList<>();
            List<PendingItem> itemPengingat = new ArrayList<>();
            List<PendingItem> daftarBelumDilihat = new ArrayList<>();
            Set<String> pendingKeysNow = new HashSet<>();
            int jumlahBaru = 0;

            for (PendingItem item : semua) {
                String key = item.jenis + ":" + item.refId;
                pendingKeysNow.add(key);

                if (!seenIds.contains(key)) {
                    appendSeenId(key);
                    jumlahBaru++;
                    lastAlarmedAt.put(key, now);

                    if (firstRun) {
                        // Baseline instalasi pertama: dicatat sebagai sudah dilihat, TIDAK dinotifikasi
                        // (tapi timer pengingat tetap mulai berjalan, jadi tetap diingatkan nanti kalau masih pending).
                        continue;
                    }

                    itemBaru.add(item);
                } else if (repeatMillis > 0) {
                    // Item lama yang masih pending: cek apakah sudah waktunya diingatkan lagi.
                    Long terakhir = lastAlarmedAt.get(key);
                    if (terakhir == null) {
                        // Belum pernah tercatat kapan terakhir diingatkan (mis. item ini sudah
                        // "sudah dilihat" dari SEBELUM fitur pengingat berkala ini ada, atau
                        // aplikasi baru saja direstart sehingga lastAlarmedAt kosong lagi).
                        // Mulai hitung timernya dari sekarang -- BUKAN langsung dianggap sudah
                        // waktunya, supaya tidak langsung membanjiri alarm untuk semua item lama
                        // begitu fitur ini pertama kali aktif / begitu aplikasi baru saja jalan.
                        lastAlarmedAt.put(key, now);
                    } else if (now - terakhir >= repeatMillis) {
                        lastAlarmedAt.put(key, now);
                        itemPengingat.add(item);
                    }
                }

                // Daftar "Belum Dilihat" selalu dibangun ulang dari status pending SAAT INI
                // (bukan cuma ditambah tiap ada yang baru) -- supaya tetap akurat walau
                // program ini baru saja direstart (daftar di memori otomatis kosong lagi
                // saat restart, padahal status pending & id yang sudah pernah dilihat di
                // seen_ids.txt tetap ada).
                daftarBelumDilihat.add(item);
            }

            // Item yang sudah tidak pending lagi (sudah dilayani) tidak perlu diingatkan lagi.
            lastAlarmedAt.keySet().retainAll(pendingKeysNow);

            pendingToShow.clear();
            pendingToShow.addAll(daftarBelumDilihat);

            if (firstRun) {
                firstRun = false;
                System.out.println("[NotifKhanzaClient] Baseline awal dicatat: " + jumlahBaru + " item pending (tidak dinotifikasi).");
            } else {
                // Sengaja digabung jadi SATU notifikasi per kelompok kalau item lebih dari satu.
                // TrayIcon Windows tidak menumpuk beberapa balloon berturut-turut dengan baik --
                // kalau displayMessage() dipanggil banyak kali cepat-cepat, cuma yang TERAKHIR
                // yang benar-benar kelihatan, jadi kelihatan seperti "cuma sebagian yang muncul".
                // Daftar lengkapnya tetap selalu benar di jendela (klik ikon tray), notifikasi ini
                // cuma pemberitahuan ringkas.
                boolean adaBaru = !itemBaru.isEmpty();
                boolean adaPengingat = !itemPengingat.isEmpty();
                if (adaBaru) tampilkanNotifikasiRingkasan(itemBaru, false);
                if (adaPengingat) tampilkanNotifikasiRingkasan(itemPengingat, true);
                if (adaBaru || adaPengingat) {
                    mainkanAlarm();
                    // Selain balloon notifikasi + alarm, jendela daftar lengkap juga otomatis
                    // dimunculkan ke layar (bukan cuma nunggu diklik) -- bisa dimatikan lewat
                    // popup.auto=false di config.properties kalau tidak diinginkan.
                    if (!"false".equalsIgnoreCase(config.getProperty("popup.auto", "true").trim())) {
                        tampilkanDaftarPending();
                    }
                }
            }

            if (listDialog != null && listDialog.isVisible()) {
                SwingUtilities.invokeLater(NotifKhanzaClient::refreshTabelDaftar);
            }
        } catch (Exception e) {
            System.err.println("[NotifKhanzaClient] Polling gagal: " + e.getMessage());
        }
    }

    static void tampilkanNotifikasiRingkasan(List<PendingItem> itemBaru, boolean pengingat) {
        String awalan = pengingat ? "Pengingat: " : "";
        if (itemBaru.size() == 1) {
            PendingItem item = itemBaru.get(0);
            trayIcon.displayMessage(
                awalan + labelJenis(item.jenis) + (item.kategori != null ? " (" + item.kategori + ")" : ""),
                item.namaPasien + " - " + item.asal + "\nNo. Rawat: " + item.noRawat,
                TrayIcon.MessageType.WARNING
            );
            return;
        }

        Map<String, Integer> hitungPerJenis = new LinkedHashMap<>();
        for (PendingItem item : itemBaru) {
            hitungPerJenis.merge(item.jenis, 1, Integer::sum);
        }
        StringBuilder rincian = new StringBuilder();
        for (Map.Entry<String, Integer> e : hitungPerJenis.entrySet()) {
            if (rincian.length() > 0) rincian.append(", ");
            rincian.append(e.getValue()).append(" ").append(labelJenis(e.getKey()));
        }

        trayIcon.displayMessage(
            awalan + itemBaru.size() + (pengingat ? " Permintaan Masih Menunggu" : " Permintaan Baru"),
            rincian + "\nKlik ikon ini untuk lihat daftar lengkap.",
            TrayIcon.MessageType.WARNING
        );
    }

    static String labelJenis(String jenis) {
        switch (jenis) {
            case "lab": return "Permintaan Lab";
            case "radiologi": return "Permintaan Radiologi";
            case "farmasi": return "Resep Belum Terlayani";
            default: return jenis;
        }
    }

    static List<String> daftarDariConfig(String key, String defaultVal) {
        String raw = config.getProperty(key, defaultVal).trim();
        List<String> hasil = new ArrayList<>();
        if (raw.isEmpty()) return hasil;
        for (String bagian : raw.split(",")) {
            String t = bagian.trim().toLowerCase();
            if (!t.isEmpty()) hasil.add(t);
        }
        return hasil;
    }

    static Connection bukaKoneksi() throws SQLException {
        String url = "jdbc:mysql://" + config.getProperty("db.host", "localhost")
                + ":" + config.getProperty("db.port", "3306")
                + "/" + config.getProperty("db.database", "rscnd")
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
        return DriverManager.getConnection(url, config.getProperty("db.username"), config.getProperty("db.password"));
    }

    static List<PendingItem> queryPendingLab(Connection conn) throws SQLException {
        List<PendingItem> hasil = new ArrayList<>();
        String[][] tabelKategori = {
            {"permintaan_lab", "Umum"},
            {"permintaan_labpa", "Patologi Anatomi"},
            {"permintaan_labmb", "Mikrobiologi"},
        };
        for (String[] tk : tabelKategori) {
            hasil.addAll(queryPending(conn, tk[0], "noorder", "tgl_hasil", "'0000-00-00'",
                    "tgl_permintaan", "jam_permintaan", "lab", tk[1]));
        }
        return hasil;
    }

    static List<PendingItem> queryPending(Connection conn, String table, String pkCol, String pendingCol, String pendingVal,
                                           String tglField, String jamField, String jenis, String kategori) throws SQLException {
        String sql = "SELECT t." + pkCol + " as ref_id, t.no_rawat, t." + tglField + " as tanggal, t." + jamField + " as jam, "
                + "r.no_rkm_medis as no_rm, p.nm_pasien, r.status_lanjut, pk.nm_poli "
                + "FROM " + table + " t "
                + "LEFT JOIN reg_periksa r ON r.no_rawat = t.no_rawat "
                + "LEFT JOIN pasien p ON p.no_rkm_medis = r.no_rkm_medis "
                + "LEFT JOIN poliklinik pk ON pk.kd_poli = r.kd_poli "
                + "WHERE t." + pendingCol + " = " + pendingVal + " "
                + "ORDER BY t." + tglField + " DESC, t." + jamField + " DESC "
                + "LIMIT 5000";

        List<PendingItem> hasil = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                PendingItem item = new PendingItem();
                item.jenis = jenis;
                item.kategori = kategori;
                item.refId = rs.getString("ref_id");
                item.noRawat = rs.getString("no_rawat");
                item.noRm = nvl(rs.getString("no_rm"));
                item.namaPasien = nvl(rs.getString("nm_pasien"), "Tidak diketahui");
                item.statusLanjut = nvl(rs.getString("status_lanjut"));
                item.tanggal = rs.getString("tanggal");
                item.jam = rs.getString("jam");

                if ("Ranap".equals(item.statusLanjut)) {
                    item.asal = cariBangsal(conn, item.noRawat);
                } else {
                    item.asal = nvl(rs.getString("nm_poli"), "Poliklinik (tidak diketahui)");
                }
                hasil.add(item);
            }
        }
        return hasil;
    }

    static String cariBangsal(Connection conn, String noRawat) {
        String sql = "SELECT b.nm_bangsal FROM kamar_inap ki "
                + "JOIN kamar k ON k.kd_kamar = ki.kd_kamar "
                + "JOIN bangsal b ON b.kd_bangsal = k.kd_bangsal "
                + "WHERE ki.no_rawat = ? "
                + "ORDER BY ki.tgl_masuk DESC, ki.jam_masuk DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, noRawat);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("nm_bangsal");
            }
        } catch (SQLException e) {
            System.err.println("Gagal mencari bangsal: " + e.getMessage());
        }
        return "Ruang rawat inap (tidak diketahui)";
    }

    static String nvl(String v) { return nvl(v, "-"); }
    static String nvl(String v, String fallback) { return (v == null || v.isEmpty()) ? fallback : v; }

    // ------------------------------------------------------------------
    // Jendela daftar pending (dibuka saat notifikasi/ikon tray diklik)
    // ------------------------------------------------------------------

    static void tampilkanDaftarPending() {
        SwingUtilities.invokeLater(() -> {
            if (listDialog == null) {
                buatListDialog();
            }
            refreshTabelDaftar();
            listDialog.setVisible(true);
            // Trik supaya jendela benar-benar naik ke depan biarpun user sedang fokus di
            // aplikasi lain (mis. Khanza) -- toFront() saja kadang tidak cukup di Windows
            // karena OS biasanya mencegah aplikasi "mencuri fokus" begitu saja.
            listDialog.setAlwaysOnTop(true);
            listDialog.toFront();
            listDialog.requestFocus();
            listDialog.setAlwaysOnTop(false);
        });
    }

    static void buatListDialog() {
        listDialog = new JDialog((Frame) null, "Notifikasi Khanza - Daftar Belum Dilihat", false);
        listDialog.setSize(700, 380);
        listDialog.setLocationRelativeTo(null);

        String[] kolom = {"Jenis", "No. Rawat", "No. RM", "Nama Pasien", "Asal", "Waktu"};
        tableModel = new DefaultTableModel(kolom, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        JScrollPane scroll = new JScrollPane(table);

        JButton btnDetail = new JButton("Lihat Detail");
        JButton btnTutup = new JButton("Tutup");

        btnDetail.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            tampilkanDetail(pendingToShow.get(row));
        });

        btnTutup.addActionListener(e -> listDialog.setVisible(false));

        JPanel tombolPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        tombolPanel.add(btnDetail);
        tombolPanel.add(btnTutup);

        listDialog.setLayout(new BorderLayout());
        listDialog.add(scroll, BorderLayout.CENTER);
        listDialog.add(tombolPanel, BorderLayout.SOUTH);
    }

    static void refreshTabelDaftar() {
        if (tableModel == null) return;
        tableModel.setRowCount(0);
        for (PendingItem item : pendingToShow) {
            tableModel.addRow(new Object[]{
                labelJenis(item.jenis) + (item.kategori != null ? " (" + item.kategori + ")" : ""),
                item.noRawat, item.noRm, item.namaPasien, item.asal,
                item.tanggal + " " + item.jam
            });
        }
    }

    static void tampilkanDetail(PendingItem item) {
        JDialog dlg = new JDialog(listDialog, "Detail Permintaan", true);
        dlg.setSize(380, 300);
        dlg.setLocationRelativeTo(listDialog);

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        panel.add(new JLabel("<html><b>" + labelJenis(item.jenis) + (item.kategori != null ? " (" + item.kategori + ")" : "") + "</b></html>"));
        panel.add(new JLabel("No. Rawat: " + item.noRawat));
        panel.add(new JLabel("No. RM: " + item.noRm));
        panel.add(new JLabel("Nama Pasien: " + item.namaPasien));
        panel.add(new JLabel("Status: " + item.statusLanjut));
        panel.add(new JLabel("Asal: " + item.asal));
        panel.add(new JLabel("Tanggal: " + item.tanggal + " " + item.jam));

        JButton btnSalin = new JButton("Salin No. Rawat");
        btnSalin.addActionListener(e -> {
            StringSelection sel = new StringSelection(item.noRawat);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            JOptionPane.showMessageDialog(dlg, "No. Rawat disalin ke clipboard, silakan paste di pencarian Khanza.");
        });
        panel.add(btnSalin);

        dlg.add(panel);
        dlg.setVisible(true);
    }

    // ------------------------------------------------------------------

    static class PendingItem {
        String jenis, kategori, refId, noRawat, noRm, namaPasien, statusLanjut, asal, tanggal, jam;
    }
}
