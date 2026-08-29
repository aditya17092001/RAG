import { useState } from "react";
import Auth from "./Auth";
import Chat from "./Chat";
import { getToken } from "./api";

export default function App() {
  const [loggedIn, setLoggedIn] = useState(!!getToken());

  return loggedIn ? (
    <Chat onLogout={() => setLoggedIn(false)} />
  ) : (
    <Auth onLogin={() => setLoggedIn(true)} />
  );
}
