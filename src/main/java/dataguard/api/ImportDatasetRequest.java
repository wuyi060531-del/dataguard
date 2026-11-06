package dataguard.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImportDatasetRequest(@NotBlank @Size(max = 240) String path) {
}
