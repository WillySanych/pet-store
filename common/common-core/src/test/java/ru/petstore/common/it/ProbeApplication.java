package ru.petstore.common.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Minimal sample service: only to check that common-core wires itself up. */
@SpringBootApplication
public class ProbeApplication {

    @RestController
    static class ProbeController {

        @GetMapping("/probe/{id}")
        String probe(@PathVariable String id) {
            return "ok:" + id;
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("deliberate failure for the error format check");
        }
    }
}
