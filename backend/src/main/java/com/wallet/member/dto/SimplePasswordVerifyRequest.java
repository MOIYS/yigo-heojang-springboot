package com.wallet.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SimplePasswordVerifyRequest(
    @NotBlank(message = "간편비밀번호는 필수입니다.")
    @Pattern(regexp = "^[0-9]{6}$", message = "간편비밀번호는 6자리 숫자여야 합니다.")
    String simplePassword
) {
}
