package dev.forgetme.demo;

import dev.forgetme.connector.ErasureHandler;
import dev.forgetme.connector.ErasureResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stage 2: files people uploaded, one folder per customer, on this service's disk. */
@RestController
@Profile("uploads")
class UploadsSystem implements ErasureHandler {

    private final Path root = Path.of(System.getProperty("java.io.tmpdir"), "forgetme-demo-uploads");

    UploadsSystem() throws IOException {
        for (String email : List.of("alice@example.com", "bob@example.com")) {
            Files.createDirectories(root.resolve(email));
            Files.writeString(root.resolve(email).resolve("profile-photo.jpg"), "(pretend this is a photo)");
        }
    }

    @GetMapping("/data")
    List<String> data() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
        }
    }

    @Override
    public ErasureResult erase(Subject subject) throws IOException {
        Path folder = root.resolve(subject.email()).normalize();
        if (!folder.startsWith(root) || folder.equals(root)) throw new IllegalArgumentException("not a customer folder");
        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete); // files first, then the folder
            }
        }
        return ErasureResult.deleted();
    }
}
