const SAMPLES = {
  kira: `module "app:main"

pub class Vector2 {
    require pub x: Float32
    require pub y: Float32

    pub fx dot(other: Vector2): Float32 {
        return (x * other.x) + (y * other.y)
    }
}

fx main(): Void {
    a: Vector2 = Vector2 { 2.0, 5.0 }
    b: Vector2 = Vector2 { 3.0, 4.0 }
    trace(a.dot(b))
}`,
  c: `/* C-as-IR excerpt (user layer) */
typedef struct Vector2 Vector2;

struct Vector2
{
    Float32 x;
    Float32 y;
};

Float32 Vector2_dot(Vector2* this, Vector2 other)
{
    return ((this->x * other.x) + (this->y * other.y));
}

Int32 main(Void)
{
    Vector2 a = (Vector2) { 2.0, 5.0 };
    Vector2 b = (Vector2) { 3.0, 4.0 };
    print("%f\\n", Vector2_dot(&a, b));
    return 0;
}`,
};

const KW = new Set([
  "module", "pub", "class", "require", "fx", "return", "trace",
  "typedef", "struct", "Float32", "Void", "Int32",
]);

function escapeHtml(s) {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function highlight(src) {
  return src.split("\n").map((line) => {
    if (line.trimStart().startsWith("/*") || line.trimStart().startsWith("*") || line.includes("*/")) {
      return `<span class="line"><span class="cm">${escapeHtml(line)}</span></span>`;
    }
    // crude token paint: keywords bold white, types slightly brighter
    let html = escapeHtml(line);
    html = html.replace(
      /\b(module|pub|class|require|fx|return|trace|typedef|struct)\b/g,
      '<span class="kw">$1</span>'
    );
    html = html.replace(
      /\b(Vector2|Float32|Void|Int32)\b/g,
      '<span class="ty">$1</span>'
    );
    return `<span class="line">${html || " "}</span>`;
  }).join("");
}

const codeView = document.getElementById("code-view");
const copyBtn = document.getElementById("copy-btn");
const tabs = [...document.querySelectorAll(".target[data-target]")];

let current = "kira";

function render(target) {
  current = target;
  codeView.innerHTML = highlight(SAMPLES[target] || "");
  tabs.forEach((tab) => {
    const on = tab.dataset.target === target;
    tab.classList.toggle("is-active", on);
    tab.setAttribute("aria-selected", on ? "true" : "false");
  });
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => render(tab.dataset.target));
});

copyBtn.addEventListener("click", async () => {
  const text = SAMPLES[current] || "";
  try {
    await navigator.clipboard.writeText(text);
    copyBtn.textContent = "Copied";
    setTimeout(() => {
      copyBtn.textContent = "Copy";
    }, 1200);
  } catch {
    copyBtn.textContent = "Failed";
    setTimeout(() => {
      copyBtn.textContent = "Copy";
    }, 1200);
  }
});

render("kira");
