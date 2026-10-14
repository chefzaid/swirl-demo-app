package dev.swirlit.demo.user.service;

import java.util.List;
import java.util.Locale;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.exception.ResourceConflictException;
import dev.swirlit.demo.user.domain.User;
import dev.swirlit.demo.user.dto.CreateUserRequest;
import dev.swirlit.demo.user.dto.UpdateUserRequest;
import dev.swirlit.demo.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final String RESOURCE = "user";

    private final UserRepository userRepository;
    private final AuditTrail auditTrail;

    public UserService(UserRepository userRepository, AuditTrail auditTrail) {
        this.userRepository = userRepository;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<User> getAllUsers(int limit) {
        return userRepository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "name"))).getContent();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "users", key = "#userId", sync = true)
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User %d was not found".formatted(userId)));
    }

    @Transactional
    @CacheEvict(cacheNames = "users", allEntries = true)
    public User createUser(CreateUserRequest request) {
        String username = normalize(request.username());
        String email = normalize(request.email());
        if (userRepository.existsByUsername(username)) {
            throw new ResourceConflictException("The username is already in use");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResourceConflictException("The email address is already in use");
        }

        User saved = userRepository.save(new User(request.name().trim(), username, email));
        auditTrail.changed("user.created", RESOURCE, saved.getId());
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = "users", allEntries = true)
    public User updateUser(Long userId, UpdateUserRequest request) {
        User user = findUser(userId);
        String username = normalize(request.username());
        String email = normalize(request.email());
        if (userRepository.existsByUsernameAndIdNot(username, userId)) {
            throw new ResourceConflictException("The username is already in use");
        }
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
            throw new ResourceConflictException("The email address is already in use");
        }

        user.setName(request.name().trim());
        user.setUsername(username);
        user.setEmail(email);
        auditTrail.changed("user.updated", RESOURCE, userId);
        return user;
    }

    @Transactional
    @CacheEvict(cacheNames = "users", allEntries = true)
    public void deleteUser(Long userId) {
        userRepository.delete(findUser(userId));
        auditTrail.changed("user.deleted", RESOURCE, userId);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User %d was not found".formatted(userId)));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
