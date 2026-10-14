package dev.swirlit.demo.user.controller;

import java.net.URI;
import java.util.List;

import dev.swirlit.demo.user.dto.CreateUserRequest;
import dev.swirlit.demo.user.dto.UpdateUserRequest;
import dev.swirlit.demo.user.dto.UserResponse;
import dev.swirlit.demo.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> getAllUsers(
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        return userService.getAllUsers(limit).stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable @Positive Long id) {
        return UserResponse.from(userService.getUser(id));
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        var created = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.getId()))
                .body(UserResponse.from(created));
    }

    @PutMapping("/{id}")
    public UserResponse updateUser(
            @PathVariable @Positive Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@userAccess.canDeleteUsers(authentication)")
    public ResponseEntity<Void> deleteUser(@PathVariable @Positive Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
