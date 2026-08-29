# Spring Security & JWT — Learn From Scratch

A beginner-friendly guide that explains Spring Security concepts first, then builds JWT authentication step by step.

---

## Part 1: Understanding Security (No Code Yet)

### What is Authentication vs Authorization?

```
Authentication = "Who are you?"     (login)
Authorization  = "What can you do?" (permissions)
```

Real-world analogy:
- **Authentication**: Showing your ID at a building entrance
- **Authorization**: Your ID gives you access to floor 3 but not floor 5

---

### What Happens Without Security?

```
Any person → GET /ask?question=...&userId=anyone → Gets data

Problem: I can put userId=aditya and see Aditya's private documents!
```

### What Happens With Security?

```
Person → Login → Gets a signed token (JWT)
Person → GET /ask (with token in header) → Server verifies token → Extracts real userId → Returns only their data

No way to fake someone else's identity because the token is cryptographically signed.
```

---

### What is a JWT Token?

JWT = JSON Web Token. It's just a string with 3 parts separated by dots:

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3MDVjNTNiMi0xOTk2LTQ0MjMtYTYyOS1kYWJhMWZhNTJkZTYiLCJlbWFpbCI6ImFkaXR5YUB0ZXN0LmNvbSJ9.abc123signature
|_________________________|_______________________________________________|__________________|
         HEADER                              PAYLOAD                           SIGNATURE
```

**Header**: Algorithm used (HS256)
**Payload**: Your data (userId, email, expiry)
**Signature**: Proof that this token was created by YOUR server (nobody can forge it)

Decoded payload:
```json
{
  "sub": "705c53b2-1996-4423-a629-daba1fa52de6",
  "email": "aditya@test.com",
  "iat": 1692489600,
  "exp": 1692576000
}
```

You can decode any JWT at https://jwt.io — but you can't modify it because the signature would break.

---

### How Spring Security Works (The Big Picture)

Spring Security is a chain of **filters** that run BEFORE your controller code:

```
HTTP Request arrives
       │
       ▼
┌─────────────────┐
│  Filter 1       │  ← Security filters
│  Filter 2       │
│  JwtAuthFilter  │  ← YOUR custom filter (reads JWT token)
│  Filter N       │
└─────────────────┘
       │
       ▼
   Is the user authenticated?
       │
   ┌───┴───┐
   │       │
  YES      NO
   │       │
   ▼       ▼
Controller  403 Forbidden
   runs     (rejected)
```

Think of it as a **security guard** standing between the internet and your controllers. Every request must pass through the guard first.

---

### Key Spring Security Concepts

| Concept | What it is | Analogy |
|---------|-----------|---------|
| `SecurityFilterChain` | The chain of security checks | The security checkpoint |
| `Authentication` | Object holding "who is this user" | Your verified ID badge |
| `SecurityContext` | Where the Authentication is stored for the current request | The badge scanner's memory |
| `UserDetailsService` | How Spring loads user info from your DB | The guard's employee database |
| `PasswordEncoder` | Hashes passwords (never stores plain text) | A one-way encryption box |
| `Filter` | Code that runs on every request before controllers | A checkpoint gate |

---

### Password Hashing — Why?

Never store passwords as plain text in a database.

```
User registers with password: "hello123"
                    │
                    ▼
         BCrypt hashes it: "$2a$10$N9qo8uLOickgx2ZMRZoMy..."
                    │
                    ▼
         STORED IN DATABASE (impossible to reverse)

User logs in with: "hello123"
                    │
                    ▼
         BCrypt hashes it again and COMPARES with stored hash
                    │
                Match? → Login success
                No?    → 401 Unauthorized
```

Even if someone steals your database, they can't read passwords.

---

## Part 2: The Flow (How JWT Auth Works End-to-End)

### Registration Flow:

```
POST /auth/register { email: "a@test.com", password: "hello123", name: "Aditya" }
       │
       ▼
1. Check: does email already exist in DB? → If yes, return 409 Conflict
       │
       ▼
2. Hash password: "hello123" → "$2a$10$N9qo8uLOickgx2ZMRZoMy..."
       │
       ▼
3. Save user in DB: { id: UUID-generated, email: "a@test.com", password: hashed, name: "Aditya" }
       │
       ▼
4. Generate JWT token: sign({ sub: userId, email: "a@test.com" }, secret)
       │
       ▼
5. Return: { token: "eyJ...", userId: "705c...", email: "a@test.com" }
```

### Login Flow:

```
POST /auth/login { email: "a@test.com", password: "hello123" }
       │
       ▼
1. Load user from DB by email
       │
       ▼
2. Compare: BCrypt.matches("hello123", storedHash) → true? continue : 401
       │
       ▼
3. Generate fresh JWT token
       │
       ▼
4. Return: { token: "eyJ...", userId: "705c...", email: "a@test.com" }
```

### Protected Request Flow:

```
GET /ask?question=What is pizza?
Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
       │
       ▼
1. JwtAuthFilter intercepts the request
       │
       ▼
2. Extracts token from "Authorization: Bearer <token>"
       │
       ▼
3. Decodes token → gets email
       │
       ▼
4. Loads user from DB by email (to confirm they still exist)
       │
       ▼
5. Verifies signature (was this token made by OUR server?)
       │
       ▼
6. Checks expiration (is token still valid?)
       │
       ▼
7. Sets SecurityContext: "This request is from userId 705c..."
       │
       ▼
8. Controller runs → reads userId from SecurityContext
       │
       ▼
9. Uses userId for ChromaDB filter → returns only their data
```

---

## Part 3: Building It (Step by Step)

### File structure you'll create:

```
src/main/java/com/aditya/rag/
├── entity/
│   └── User.java                 ← The user table
├── repository/
│   └── UserRepository.java       ← Database queries
├── security/
│   ├── JwtService.java           ← Create & validate tokens
│   ├── JwtAuthFilter.java        ← Intercept requests, check tokens
│   └── SecurityConfig.java       ← Define rules (what's public, what needs auth)
├── dto/
│   ├── AuthRequest.java          ← Request body for login/register
│   └── AuthResponse.java         ← Response with token
└── controller/
    └── AuthController.java       ← /auth/register, /auth/login
```

### Build Order (follow this sequence):

```
1. Entity + Repository    (where users are stored)
      ↓
2. JwtService             (how tokens are created/read)
      ↓
3. JwtAuthFilter          (how requests are intercepted)
      ↓
4. SecurityConfig         (which URLs are public vs protected)
      ↓
5. AuthController         (register + login endpoints)
      ↓
6. Update existing        (remove userId param from controllers)
```

---

### Step 1: User Entity

```java
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
    private String password;   // Always stored as BCrypt hash, NEVER plain text

    private String name;

    // Spring Security requires these methods:

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getUsername() {
        return email;  // Spring Security uses "username" — we use email as our username
    }
}
```

**Why implement `UserDetails`?**
Spring Security needs to load users during authentication. `UserDetails` is the interface it expects. By putting it directly on the entity, we avoid an extra adapter class.

**What are `getAuthorities()`?**
Roles/permissions. We just give everyone "ROLE_USER" for now. Later you could add "ROLE_ADMIN" for admin-only features.

---

### Step 2: Repository

```java
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

Spring Data JPA auto-generates the SQL from method names. No implementation needed.

---

### Step 3: JWT Service

```java
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;   // Your secret key (from application.properties)

    @Value("${app.jwt.expiration}")
    private long expiration; // Token lifetime in milliseconds

    // CREATE a token
    public String generateToken(UUID userId, String email) {
        return Jwts.builder()
                .subject(userId.toString())          // "sub" claim
                .claims(Map.of("email", email))      // custom claim
                .issuedAt(new Date())                // when created
                .expiration(new Date(System.currentTimeMillis() + expiration))  // when it expires
                .signWith(getSigningKey())           // sign with our secret
                .compact();                          // build the string
    }

    // READ userId from token
    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).getSubject());
    }

    // READ email from token
    public String extractEmail(String token) {
        return extractClaims(token).get("email", String.class);
    }

    // VALIDATE a token
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String email = extractEmail(token);
        return email.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }

    // Decode the token (verifies signature automatically — throws if tampered)
    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Convert string secret to a cryptographic key
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
```

**Key insight**: The `signWith(key)` step is what makes the token unforgeable. Without knowing your secret key, nobody can create a valid token.

---

### Step 4: JWT Auth Filter

This is the "security guard" that checks every request:

```java
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

        // 1. Get the Authorization header
        String authHeader = request.getHeader("Authorization");

        // 2. No token? Let the request through (SecurityConfig will reject if needed)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Extract the token (remove "Bearer " prefix)
        String token = authHeader.substring(7);
        String email = jwtService.extractEmail(token);

        // 4. If we got an email and user isn't already authenticated
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            // 5. Load user from database
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            // 6. Validate the token
            if (jwtService.isTokenValid(token, userDetails)) {

                // 7. Tell Spring Security: "This user is authenticated"
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                jwtService.extractUserId(token).toString(),  // principal (who)
                                null,                                         // credentials (not needed)
                                userDetails.getAuthorities()                  // roles
                        );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // 8. Continue to next filter / controller
        filterChain.doFilter(request, response);
    }
}
```

**The most important line:**
```java
SecurityContextHolder.getContext().setAuthentication(authToken);
```
This is how your controller later knows who the user is. It's like stamping a badge on the request.

---

### Step 5: Security Config

This defines the RULES:

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF (not needed for stateless JWT APIs)
            .csrf(csrf -> csrf.disable())

            // Define URL access rules
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()        // login/register = public
                .requestMatchers("/h2-console/**").permitAll()  // DB console = public
                .anyRequest().authenticated()                   // everything else = need token
            )

            // No sessions (stateless — every request carries its own token)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Use our custom filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // How to load users from DB
    @Bean
    public UserDetailsService userDetailsService() {
        return email -> userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    // How to check passwords
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Spring Security's auth manager
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

**Why `csrf.disable()`?**
CSRF protection is for browser-based forms with sessions. Since we use JWT (stateless, no cookies), CSRF doesn't apply.

**Why `STATELESS`?**
Traditional web apps use sessions (server stores who's logged in). With JWT, the client holds the proof (the token). Server doesn't remember anything between requests.

---

### Step 6: Auth Controller

```java
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
        // Prevent duplicate emails
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        // Create user (password is HASHED before saving)
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))  // HASH!
                .name(request.getName())
                .build();

        user = userRepository.save(user);

        // Return token immediately (user is logged in after registering)
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail());
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        // AuthenticationManager checks email + password against DB
        // Throws BadCredentialsException if wrong → Spring returns 401
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // If we get here, credentials are valid
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow();

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getEmail());
    }
}
```

---

### Step 7: Update Your Existing Controllers

**Before** (insecure — anyone can claim to be anyone):
```java
@GetMapping("/ask")
public String ask(@RequestParam String question, @RequestParam UUID userId) {
```

**After** (secure — userId comes from verified token):
```java
@GetMapping("/ask")
public String ask(@RequestParam String question) {
    UUID userId = UUID.fromString(
        SecurityContextHolder.getContext().getAuthentication().getName()
    );
    // ... rest stays the same
}
```

Same change in `FileUploadController` — remove the `userId` parameter.

---

## Part 4: Testing the Full Flow

### 1. Register
```
POST /auth/register
Body: { "email": "aditya@test.com", "password": "pass123", "name": "Aditya" }
→ Returns: { "token": "eyJ...", "userId": "abc-123", "email": "..." }
```

### 2. Copy the token

### 3. Upload a file
```
POST /upload
Authorization: Bearer eyJ...  ← paste token here
Body: file + visibility=PRIVATE
```

### 4. Ask a question
```
GET /ask?question=What is this about?
Authorization: Bearer eyJ...  ← same token
```

### 5. Register another user
```
POST /auth/register
Body: { "email": "john@test.com", "password": "pass123", "name": "John" }
→ Returns different token with different userId
```

### 6. Try accessing aditya's private file as john
```
GET /ask?question=What is this about?
Authorization: Bearer <john's token>
→ Gets "I don't know" (can't see aditya's PRIVATE docs)
```

---

## Part 5: Common Questions

### Q: Where is the token stored on the client side?
**A:** The frontend (React, mobile app, etc.) stores it in memory or localStorage. It sends it with every request in the `Authorization` header.

### Q: What happens when the token expires?
**A:** The user gets a 401 response and must login again to get a fresh token. You can also implement a "refresh token" flow later.

### Q: Can someone steal the token?
**A:** If they have access to the client (XSS attack, stolen device), yes. That's why tokens expire. In production, use HTTPS, short expiry times, and refresh tokens.

### Q: Why not just use sessions?
**A:** Sessions are server-side (server must remember each logged-in user). JWT is stateless — the client holds the proof. Better for APIs consumed by mobile apps, SPAs, and microservices.

### Q: What if I want to logout a user?
**A:** With basic JWT, you can't "invalidate" a token (it's self-contained). Options:
- Short expiry (e.g., 1 hour)
- Maintain a "blacklist" of revoked tokens in Redis/DB
- Use refresh tokens (revoke the refresh token)

---

## Part 6: Security Checklist

| Item | Status |
|------|--------|
| Passwords hashed with BCrypt | Required |
| JWT signed with a strong secret (256+ bits) | Required |
| Token has expiration | Required |
| HTTPS in production | Required |
| No secrets in code (use env variables) | Required |
| CSRF disabled (stateless API) | Required for JWT |
| CORS configured (if frontend is separate domain) | Needed for UI |
