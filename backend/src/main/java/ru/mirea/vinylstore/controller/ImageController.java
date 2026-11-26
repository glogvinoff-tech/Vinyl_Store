package ru.mirea.vinylstore.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.mirea.vinylstore.error.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/api/images")
public class ImageController {
    private final JdbcTemplate jdbc;
    public ImageController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new BadRequestException("Изображение должно быть не больше 5 МБ");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BadRequestException("Выберите изображение PNG или JPEG");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!Set.of("png", "jpeg", "jpg").contains(format.toLowerCase(Locale.ROOT))) throw new BadRequestException("Поддерживаются PNG и JPEG");
                if ((long)reader.getWidth(0) * reader.getHeight(0) > 16000000) throw new BadRequestException("Максимальный размер — 16 мегапикселей");
                var image = reader.read(0);
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "png", bytes);
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO catalog_image(id, content) VALUES (?, ?)", id, bytes.toByteArray());
                return Map.of("url", "/api/images/" + id);
            } finally { reader.dispose(); }
        } catch (javax.imageio.IIOException e) { throw new BadRequestException("Не удалось прочитать изображение"); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        var rows = jdbc.query("SELECT content FROM catalog_image WHERE id = ?", (rs, n) -> rs.getBytes(1), id);
        if (rows.isEmpty()) throw new NotFoundException("Изображение не найдено");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(rows.getFirst());
    }
}
