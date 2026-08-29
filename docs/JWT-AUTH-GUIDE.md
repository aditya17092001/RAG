# Adding JWT Authentication to the RAG Application

Step-by-step guide to securing your RAG API with JWT tokens so users can register, login, and access their own documents securely.

---

## How It Works

```
┌─────────────────────────────────────────────────────────┐
│                    REGISTER / LOGIN                       │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  POST /auth/register  { email, password }                │
│       → Creates user in DB                               │
│       → Returns JWT token                                │
│                                                           │
│  POST /auth/login  { email, password }                   │
│       → Validates credentials                            │
│       → Returns JWT token                                │
│                                                           │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    PROTECTED ENDPOINTS                    │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  POST /upload                                            │
│  Header: Authorization: Bearer <token>                   │
│       → JwtAuthFilter reads token                        │
│       → Extracts userId from token                       │
│       → No more @RequestParam userId                     │
│                                                           │
│  GET /ask?question=...                                   │
│  Header: Authorization: Bearer <token>                   │
│       → Same filter extracts userId                      │
│       → Filters documents by owner automatically         │
│                                                           │
└─────────────────────────────────────────────────────────┘
```

---

## Step 1: Add Dependencies to `pom.xml`

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT (jjwt library) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>

<!-- JPA + H2 (lightweight user database, no external setup) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

---

## Step 2: `application.properties` additions

```properties
# JWT Configuration
app.jwt.secret=my-super-secret-key-that-is-at-least-256-bits-long-for-hs256
app.jwt.expiration=86400000

# H2 Database (file-based, persists across restarts)
spring.datasource.url=jdbc:h2:file:./data/users
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect

# H2 Console (optional, for debugging - access at /h2-console)
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
```

---

## Step 3: Create User Entity

File: `src/main/java/com/aditya/rag/entity/User.java`

```java
package com.aditya.rag.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    private String name;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getUsername() {
        return email;
    }
}
```

### Why `UserDetails`?
Spring Security uses this interface to load user information during authentication. By implementing it directly on the entity, we avoid needing a separate adapter class.

---

## Step 4: Create User Repository

File: `src/main/java/com/aditya/rag/repository/UserRepository.java`

```java
package com.aditya.rag.repository;

import com.aditya.rag.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

---

## Step 5: Create JWT Service

File: `src/main/java/com/aditya/rag/security/JwtService.java`

```java
package com.aditya.rag.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration}")
    private long expiration;

    // Generate token with userId in claims
    public String generateToken(UUID userId, String email) {
        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of("email", email))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // Extract userId from token
    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).getSubject());
    }

    // Extract email from token
    public String extractEmail(String token) {
        return extractClaims(token).get("email", String.class);
    }

    // Validate token
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String email = extractEmail(token);
        return email.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
```

### What the JWT token contains:
```json
{
  "sub": "705c53b2-1996-4423-a629-daba1fa52de6",  // userId
  "email": "aditya@test.com",
  "iat": 1692489600,                                // issued at
  "exp": 1692576000                                 // expires in 24h
}
```

---

## Step 6: Create JWT Auth Filter

File: `src/main/java/com/aditya/rag/security/JwtAuthFilter.java`

```java
package com.aditya.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Get Authorization header
        String authHeader = request.getHeader("Authorization");

        // Skip if no Bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract token
        String token = authHeader.substring(7);
        String email = jwtService.extractEmail(token);

        // Authenticate if not already done
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            if (jwtService.isTokenValid(token, userDetails)) {
                // Use userId (from token subject) as the principal name
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                jwtService.extractUserId(token).toString(),  // principal = userId
                                null,
                                userDetails.getAuthorities()
                        );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### What this filter does on every request:
```
Request arrives with "Authorization: Bearer eyJhbG..."
    │
    ▼
Extract token from header
    │
    ▼
Extract email from token → Load user from DB
    │
    ▼
Validate token (signature + expiration)
    │
    ▼
Set SecurityContext (userId as principal)
    │
    ▼
Request continues to controller
```

---

## Step 7: Create Security Configuration

File: `src/main/java/com/aditya/rag/security/SecurityConfig.java`

```java
package com.aditya.rag.security;

import com.aditya.rag.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (no token needed)
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // For H2 console (optional)
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### What's public vs protected:
| Endpoint | Access |
|----------|--------|
| `POST /auth/register` | Public |
| `POST /auth/login` | Public |
| `/h2-console/**` | Public (for debugging) |
| `POST /upload` | Requires JWT |
| `GET /ask` | Requires JWT |
| Everything else | Requires JWT |

---

## Step 8: Create Auth DTOs

File: `src/main/java/com/aditya/rag/dto/AuthRequest.java`

```java
package com.aditya.rag.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String email;
    private String password;
    private String name;
}
```

File: `src/main/java/com/aditya/rag/dto/AuthResponse.java`

```java
package com.aditya.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private UUID userId;
    private String email;
}
```

---

## Step 9: Create Auth Controller

File: `src/main/java/com/aditya/rag/controller/AuthController.java`

```java
package com.aditya.rag.controller;

import com.aditya.rag.dto.AuthRequest;
import com.aditya.rag.dto.AuthResponse;
import com.aditya.rag.entity.User;
import com.aditya.rag.repository.UserRepository;
import com.aditya.rag.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        // Create user
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .build();

        user = userRepository.save(user);

        // Generate token
        String token = jwtService.generateToken(user.getId(), user.getEmail());

        return new AuthResponse(token, user.getId(), user.getEmail());
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        // Authenticate (throws if invalid)
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // Load user
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Generate token
        String token = jwtService.generateToken(user.getId(), user.getEmail());

        return new AuthResponse(token, user.getId(), user.getEmail());
    }
}
```

---

## Step 10: Update Existing Controllers

### Remove `@RequestParam UUID userId` — get it from SecurityContext instead:

**In both `RagController` and `FileUploadController`, replace:**

```java
@RequestParam UUID userId
```

**With this helper method:**

```java
private UUID getCurrentUserId() {
    return UUID.fromString(
        SecurityContextHolder.getContext().getAuthentication().getName()
    );
}
```

Import: `org.springframework.security.core.context.SecurityContextHolder`

**Example updated RagController:**

```java
@GetMapping("/ask")
public String ask(@RequestParam String question) {
    UUID userId = getCurrentUserId();  // from JWT, not from param

    // ... rest stays the same
}
```

**Example updated FileUploadController:**

```java
@PostMapping("/upload")
public Map<String, Object> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(defaultValue = "PUBLIC") String visibility
) {
    UUID userId = getCurrentUserId();  // from JWT, not from param

    // ... rest stays the same
}
```

---

## Testing with Postman

### 1. Register

```
POST http://localhost:8080/auth/register
Content-Type: application/json

{
    "email": "aditya@test.com",
    "password": "password123",
    "name": "Aditya"
}
```

Response:
```json
{
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": "705c53b2-1996-4423-a629-daba1fa52de6",
    "email": "aditya@test.com"
}
```

### 2. Login

```
POST http://localhost:8080/auth/login
Content-Type: application/json

{
    "email": "aditya@test.com",
    "password": "password123"
}
```

Response: same as register (returns fresh token).

### 3. Upload (with token)

```
POST http://localhost:8080/upload
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data

file: <select file>
visibility: PRIVATE
```

Note: No more `userId` field needed — it comes from the token!

### 4. Ask (with token)

```
GET http://localhost:8080/ask?question=What is a data structure?
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### In Postman:
1. Go to **Authorization** tab
2. Type: **Bearer Token**
3. Paste the token from login/register response

---

## How the Security Flow Works

```
┌────────────────────────────────────────────────────────────────┐
│ Request: GET /ask?question=...                                  │
│ Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...          │
├────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. JwtAuthFilter runs first                                    │
│     ├── Extracts token from header                              │
│     ├── Decodes: subject=userId, email=aditya@test.com          │
│     ├── Loads user from DB by email                             │
│     ├── Validates signature + expiration                        │
│     └── Sets SecurityContext.authentication.name = userId       │
│                                                                  │
│  2. SecurityConfig checks: is this endpoint authenticated?      │
│     └── Yes, SecurityContext has a valid auth → allow           │
│                                                                  │
│  3. RagController.ask() runs                                    │
│     ├── getCurrentUserId() → reads from SecurityContext         │
│     ├── Uses userId for vector store filter                     │
│     └── Returns answer                                          │
│                                                                  │
└────────────────────────────────────────────────────────────────┘
```

---

## Project Structure After This

```
src/main/java/com/aditya/rag/
├── LocalRagApplication.java
├── config/
│   └── ChatMemoryConfig.java
├── controller/
│   ├── AuthController.java          <-- NEW
│   ├── FileUploadController.java    <-- UPDATED (remove userId param)
│   └── RagController.java           <-- UPDATED (remove userId param)
├── dto/
│   ├── AuthRequest.java             <-- NEW
│   └── AuthResponse.java            <-- NEW
├── entity/
│   └── User.java                    <-- NEW
├── repository/
│   └── UserRepository.java          <-- NEW
├── security/
│   ├── JwtService.java              <-- NEW
│   ├── JwtAuthFilter.java           <-- NEW
│   └── SecurityConfig.java          <-- NEW
└── service/
    └── DataIngestionService.java
```

---

## Summary

| What | Before | After |
|------|--------|-------|
| User identity | `@RequestParam userId` (fakeable) | Extracted from signed JWT (unforgeable) |
| Authentication | None | Email + password → BCrypt hashed |
| Endpoint access | Open to everyone | Token required |
| User storage | None | H2 file database |
| Token expiry | N/A | 24 hours (configurable) |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| 403 Forbidden on `/upload` | You forgot to send the `Authorization: Bearer <token>` header |
| 401 Unauthorized | Token is expired or invalid — login again |
| H2 console not accessible | Make sure `/h2-console/**` is in `permitAll()` |
| "User not found" on login | Register first at `/auth/register` |
| Token not working after restart | H2 file DB persists users, but if you changed the JWT secret, old tokens are invalid |
