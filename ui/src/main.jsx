import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.jsx";

// no StrictMode: double-mount would open two WebSockets (connectWs has module-level state)
createRoot(document.getElementById("root")).render(<App />);