import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;

class JpackageUtf8 {
    public static void main(String[] args) throws Exception {
        // The JDK 17 Windows launcher uses the system code page for its command line.
        // Pass each option through a UTF-8 file so product and directory names survive.
        String[] options = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8).toArray(String[]::new);
        int status = ToolProvider.findFirst("jpackage").orElseThrow().run(System.out, System.err, options);
        System.exit(status);
    }
}
