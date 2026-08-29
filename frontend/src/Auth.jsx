import { useState } from "react";
import { signup, signin, verifyOtp, setToken } from "./api";

export default function Auth({ onLogin }) {
  // mode: "signin" | "signup" | "verify"
  const [mode, setMode] = useState("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [otp, setOtp] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [loading, setLoading] = useState(false);

  const reset = () => {
    setError("");
    setSuccess("");
  };

  const handleSignin = async () => {
    reset();
    setLoading(true);
    try {
      const res = await signin(email, password);
      setToken(res.token);
      onLogin();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleSignup = async () => {
    reset();
    setLoading(true);
    try {
      await signup(email, password, name);
      setSuccess("Signup successful! Check your email for the OTP.");
      setMode("verify");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleVerify = async () => {
    reset();
    setLoading(true);
    try {
      await verifyOtp(email, otp);
      setSuccess("Email verified! You can now sign in.");
      setMode("signin");
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        {mode === "signin" && (
          <>
            <h2>Sign In</h2>
            <div className="field">
              <label>Email</label>
              <input value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="field">
              <label>Password</label>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            <button className="full-btn" onClick={handleSignin} disabled={loading}>
              {loading ? "Signing in..." : "Sign In"}
            </button>
            <div className="auth-switch">
              No account? <span onClick={() => { reset(); setMode("signup"); }}>Sign up</span>
            </div>
          </>
        )}

        {mode === "signup" && (
          <>
            <h2>Sign Up</h2>
            <div className="field">
              <label>Name</label>
              <input value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="field">
              <label>Email</label>
              <input value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="field">
              <label>Password (min 8 chars)</label>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            <button className="full-btn" onClick={handleSignup} disabled={loading}>
              {loading ? "Creating..." : "Sign Up"}
            </button>
            <div className="auth-switch">
              Have an account? <span onClick={() => { reset(); setMode("signin"); }}>Sign in</span>
            </div>
          </>
        )}

        {mode === "verify" && (
          <>
            <h2>Verify Email</h2>
            <div className="field">
              <label>Email</label>
              <input value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="field">
              <label>OTP (check your email or app logs)</label>
              <input value={otp} onChange={(e) => setOtp(e.target.value)} />
            </div>
            <button className="full-btn" onClick={handleVerify} disabled={loading}>
              {loading ? "Verifying..." : "Verify"}
            </button>
            <div className="auth-switch">
              Back to <span onClick={() => { reset(); setMode("signin"); }}>Sign in</span>
            </div>
          </>
        )}

        {error && <div className="error">{error}</div>}
        {success && <div className="success">{success}</div>}
      </div>
    </div>
  );
}
