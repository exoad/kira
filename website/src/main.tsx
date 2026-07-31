import { createRoot } from "react-dom/client";
import App from "./App";
const rootDiv = document.getElementById("root");
import "./index.css";
if (rootDiv) {
  const root = createRoot(rootDiv);
  root.render(<App />);
}