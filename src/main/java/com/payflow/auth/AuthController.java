package com.payflow.auth;

import com.payflow.security.JwtService;
import com.payflow.user.User;
import com.payflow.user.UserRepository;
import com.payflow.wallet.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final WalletService walletService;

    public AuthController(UserRepository users, PasswordEncoder encoder,
                          JwtService jwt, WalletService walletService) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.walletService = walletService;
    }

    public record RegisterRequest(@Email @NotBlank String email,
                                  @NotBlank @Size(min = 8) String password) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "email_taken"));
        }
        User user = users.save(new User(req.email(), encoder.encode(req.password())));
        walletService.createWallet(user.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("token", jwt.issue(user.getId(), user.getEmail())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        return users.findByEmail(req.email())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(
                        Map.of("token", jwt.issue(u.getId(), u.getEmail()))))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "invalid_credentials")));
    }
}
