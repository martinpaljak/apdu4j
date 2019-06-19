package apdu4j;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ATRList {
    private final Map<String, String> map;


    public static ATRList from(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            Map<String, String> entries = new HashMap<>();

            List<String> lines = reader.lines().collect(Collectors.toList());
            String name = null;
            String desc = null;
            for (String line : lines) {

                if (line.length() == 0) {
                    if (name != null && desc != null) {
                        entries.put(name.replace(" ", ""), desc.trim().replace('\t', '\n'));
                    }
                    desc = "";
                } else if (line.charAt(0) == '\t') {
                    desc += line;
                } else if (line.charAt(0) == '#') {
                    continue;
                } else {
                    name = line;
                }
            }

            System.out.println("Parsed " + entries.size() + " ATR-s");
            return new ATRList(entries);
        }
    }

    public static ATRList from(String path) throws IOException {
        final InputStream in;
        if (path.startsWith("http")) {
            in = new URL(path).openStream();
        } else {
            in = new FileInputStream(path);
        }
        return from(in);
    }

    private ATRList(Map<String, String> map) {
        this.map = map;
    }

    public Optional<Map.Entry<String, String>> match(byte[] atr) {
        String q = HexUtils.bin2hex(atr).toUpperCase();
        return map.entrySet().stream().filter(e -> q.matches(e.getKey())).findFirst();
    }

    // Does the same as https://github.com/LudovicRousseau/pcsc-tools/blob/master/ATR_analysis#L58
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    public static Optional<String> locate() {
        String home = System.getProperty("user.home", "");
        Path[] paths = new Path[]{
                Paths.get(home, ".cache", ".smartcard_list.txt"),
                Paths.get(home, ".smartcard_list.txt"),
                Paths.get("/usr/local/pcsc/smartcard_list.txt"),
                Paths.get("/usr/share/pcsc/smartcard_list.txt"),
                Paths.get("/usr/local/share/pcsc/smartcard_list.txt")};

        for (Path p : paths) {
            if (Files.exists(p))
                return Optional.of(p.toString());
        }

        return Optional.empty();
    }
}
