package dev.swirlit.demo.user.service;

import java.util.List;
import java.util.Optional;

import dev.swirlit.demo.user.domain.User;
import dev.swirlit.demo.user.dto.CreateUserRequest;
import dev.swirlit.demo.user.dto.UpdateUserRequest;
import dev.swirlit.demo.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import dev.swirlit.demo.common.audit.AuditTrail;
import dev.swirlit.demo.common.exception.ResourceConflictException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditTrail auditTrail;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, auditTrail);
    }

    @Test
    void getAllUsersSortsByName() {
        User user = new User("Ada", "ada", "ada@example.test");
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));

        assertEquals(List.of(user), userService.getAllUsers(25));
    }

    @Test
    void getUserReturnsProfile() {
        User user = new User("Ada", "ada", "ada@example.test");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertEquals(user, userService.getUser(1L));
    }

    @Test
    void createUserNormalizesInput() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(5L);
            return saved;
        });

        User created = userService.createUser(new CreateUserRequest(" Ada ", "ada", "ADA@EXAMPLE.TEST "));

        assertEquals(5L, created.getId());
        verify(auditTrail).changed("user.created", "user", 5L);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(captor.capture());
        assertEquals("Ada", captor.getValue().getName());
        assertEquals("ada", captor.getValue().getUsername());
        assertEquals("ada@example.test", captor.getValue().getEmail());
    }

    @Test
    void getUserRejectsUnknownId() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> userService.getUser(99L));
    }

    @Test
    void createUserFailsFastForDuplicateUsername() {
        when(userRepository.existsByUsername("ada")).thenReturn(true);

        assertThrows(ResourceConflictException.class,
                () -> userService.createUser(new CreateUserRequest("Ada", "ada", "new@example.test")));
    }

    @Test
    void createUserFailsFastForDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("ada@example.test")).thenReturn(true);

        assertThrows(ResourceConflictException.class,
                () -> userService.createUser(new CreateUserRequest("Ada", "new-user", "ADA@EXAMPLE.TEST")));
    }

    @Test
    void updateUserNormalizesAndChangesProfile() {
        User existing = new User("Ada", "ada", "ada@example.test");
        existing.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));

        User updated = userService.updateUser(
                1L, new UpdateUserRequest(" Ada Byron ", "ada.byron", "ADA.BYRON@EXAMPLE.TEST "));

        assertEquals("Ada Byron", updated.getName());
        assertEquals("ada.byron", updated.getUsername());
        assertEquals("ada.byron@example.test", updated.getEmail());
        verify(auditTrail).changed("user.updated", "user", 1L);
    }

    @Test
    void updateUserRejectsAnotherUsersUsername() {
        User existing = new User("Ada", "ada", "ada@example.test");
        existing.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.existsByUsernameAndIdNot("grace", 1L)).thenReturn(true);

        assertThrows(ResourceConflictException.class,
                () -> userService.updateUser(
                        1L, new UpdateUserRequest("Ada", "grace", "ada@example.test")));
    }

    @Test
    void updateUserRejectsAnotherUsersEmail() {
        User existing = new User("Ada", "ada", "ada@example.test");
        existing.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("grace@example.test", 1L)).thenReturn(true);

        assertThrows(ResourceConflictException.class,
                () -> userService.updateUser(
                        1L, new UpdateUserRequest("Ada", "ada", "grace@example.test")));
    }

    @Test
    void deleteUserRemovesExistingProfile() {
        User existing = new User("Ada", "ada", "ada@example.test");
        existing.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));

        userService.deleteUser(1L);

        verify(userRepository).delete(existing);
        verify(auditTrail).changed("user.deleted", "user", 1L);
    }
}
