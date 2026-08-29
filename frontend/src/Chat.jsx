import { useEffect, useRef, useState } from "react";
import {
  listConversations,
  createConversation,
  getMessages,
  deleteConversation,
  askStream,
  uploadFile,
  clearToken,
} from "./api";

export default function Chat({ onLogout }) {
  const [conversations, setConversations] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [uploadStatus, setUploadStatus] = useState("");
  const [visibility, setVisibility] = useState("PRIVATE");
  const [sidebarOpen, setSidebarOpen] = useState(false); // mobile drawer
  const messagesEndRef = useRef(null);

  // load conversations on mount
  useEffect(() => {
    refreshConversations();
  }, []);

  // scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const refreshConversations = async () => {
    try {
      const list = await listConversations();
      setConversations(list);
    } catch (e) {
      console.error(e);
    }
  };

  const openConversation = async (id) => {
    setActiveId(id);
    setSidebarOpen(false); // close drawer on mobile after selecting
    try {
      const msgs = await getMessages(id);
      setMessages(msgs);
    } catch (e) {
      setMessages([]);
    }
  };

  const handleNewChat = async () => {
    const conv = await createConversation(null);
    await refreshConversations();
    setActiveId(conv.id);
    setMessages([]);
    setSidebarOpen(false); // close drawer on mobile
  };

  const handleDelete = async (id, e) => {
    e.stopPropagation();
    await deleteConversation(id);
    if (activeId === id) {
      setActiveId(null);
      setMessages([]);
    }
    refreshConversations();
  };

  const handleSend = async () => {
    if (!input.trim() || sending) return;

    // create a conversation if none is active
    let convId = activeId;
    if (!convId) {
      const conv = await createConversation(null);
      convId = conv.id;
      setActiveId(convId);
      await refreshConversations();
    }

    const question = input.trim();
    setInput("");

    // Add the user's message + an empty assistant message that we'll stream into
    setMessages((prev) => [
      ...prev,
      { role: "user", content: question },
      { role: "assistant", content: "" },
    ]);
    setSending(true);

    try {
      // Stream tokens into the last (assistant) message as they arrive
      await askStream(question, convId, (chunk) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          updated[updated.length - 1] = { ...last, content: last.content + chunk };
          return updated;
        });
      });
      refreshConversations(); // title may have auto-updated
    } catch (e) {
      setMessages((prev) => {
        const updated = [...prev];
        updated[updated.length - 1] = { role: "assistant", content: "Error: " + e.message };
        return updated;
      });
    } finally {
      setSending(false);
    }
  };

  const handleUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setUploadStatus(`Uploading ${file.name}...`);
    try {
      const res = await uploadFile(file, visibility);
      setUploadStatus(`Uploaded ${res.filename} (${res.chunks} chunks, ${res.visibility})`);
    } catch (err) {
      setUploadStatus("Upload failed: " + err.message);
    }
    e.target.value = ""; // reset input
  };

  const handleLogout = () => {
    clearToken();
    onLogout();
  };

  return (
    <div className="app">
      {/* Dark overlay behind the drawer on mobile */}
      {sidebarOpen && <div className="overlay" onClick={() => setSidebarOpen(false)} />}

      <aside className={`sidebar ${sidebarOpen ? "open" : ""}`}>
        <button className="new-chat" onClick={handleNewChat}>+ New Chat</button>
        <div className="conv-list">
          {conversations.map((c) => (
            <div
              key={c.id}
              className={`conv-item ${activeId === c.id ? "active" : ""}`}
              onClick={() => openConversation(c.id)}
            >
              <span>{c.title || "New Chat"}</span>
              <span className="del" onClick={(e) => handleDelete(c.id, e)}>Delete</span>
            </div>
          ))}
        </div>
        <button className="logout" onClick={handleLogout}>Logout</button>
      </aside>

      <main className="main">
        <div className="upload-bar">
          {/* Hamburger — only visible on mobile via CSS */}
          <button
            type="button"
            className="menu-btn"
            onClick={() => setSidebarOpen(true)}
            aria-label="Open menu"
          >
            ☰
          </button>
          <label htmlFor="file-input">
            <button type="button" onClick={() => document.getElementById("file-input").click()}>
              Upload Document
            </button>
          </label>
          <input
            id="file-input"
            type="file"
            style={{ display: "none" }}
            onChange={handleUpload}
          />
          <select value={visibility} onChange={(e) => setVisibility(e.target.value)}>
            <option value="PRIVATE">Private</option>
            <option value="PUBLIC">Public</option>
          </select>
          <span className="status">{uploadStatus}</span>
        </div>

        <div className="messages">
          {messages.length === 0 && (
            <div className="empty">Ask a question or upload a document to get started.</div>
          )}
          {messages.map((m, i) => {
            const isStreaming =
              sending && m.role === "assistant" && i === messages.length - 1;
            return (
              <div key={i} className={`msg ${m.role}`}>
                <div className="role">{m.role === "user" ? "You" : "Assistant"}</div>
                <div className={`bubble ${isStreaming ? "cursor" : ""}`}>{m.content}</div>
              </div>
            );
          })}
          <div ref={messagesEndRef} />
        </div>

        <div className="composer">
          <textarea
            placeholder="Ask a question..."
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
          />
          <button onClick={handleSend} disabled={sending}>
            {sending ? "..." : "Send"}
          </button>
        </div>
      </main>
    </div>
  );
}
