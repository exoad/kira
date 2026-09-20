import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Sans, Serif } from "./Typograph";
import { useEffect, useState, useRef } from "react";

interface TocItem {
    id: string;
    text: string;
    level: number;
    parentId?: string; // For H3s, this is the parent H2's id
}

interface Section {
    id: string;
    title: string;
    content: string;
    subheadings: TocItem[]; // H3s within this section
}

const slugify = (text: string): string => {
    return text
        .toLowerCase()
        .replaceAll(/[^a-z0-9]+/g, "-")
        .replaceAll(/(^-|-$)/g, "");
};

const extractHeadings = (markdown: string): TocItem[] => {
    const headingRegex = /^(#{1,2})\s+(.+)$/gm;
    const headings: TocItem[] = [];
    let match;

    while ((match = headingRegex.exec(markdown)) !== null) {
        const level = match[1].length;
        const text = match[2].trim();
        headings.push({
            id: slugify(text),
            text,
            level,
        });
    }

    return headings;
};

const extractSubheadings = (content: string): TocItem[] => {
    const headingRegex = /^(#{3})\s+(.+)$/gm;
    const subheadings: TocItem[] = [];
    let match;

    while ((match = headingRegex.exec(content)) !== null) {
        const text = match[2].trim();
        subheadings.push({
            id: slugify(text),
            text,
            level: 3,
        });
    }

    return subheadings;
};

const splitIntoSections = (markdown: string): Section[] => {
    const sections: Section[] = [];
    const lines = markdown.split("\n");
    let currentSection: Section | null = null;
    let h1Title = "";

    for (const element of lines) {
        const line = element;
        if (new RegExp(/^#\s+/).exec(line)) {
            h1Title = line.replace(/^#\s+/, "").trim();
            if (currentSection) {
                sections.push(currentSection);
            }
            currentSection = null;
            continue;
        }
        if (new RegExp(/^##\s+/).exec(line)) {
            if (currentSection) {
                currentSection.subheadings = extractSubheadings(
                    currentSection.content
                );
                sections.push(currentSection);
            }
            const title = line.replace(/^##\s+/, "").trim();
            currentSection = {
                id: slugify(title),
                title: title,
                content: h1Title ? `# ${h1Title}\n\n${line}\n` : `${line}\n`,
                subheadings: [],
            };
            continue;
        }

        // Add content to current section
        if (currentSection) {
            currentSection.content += line + "\n";
        } else if (h1Title) {
            // Content before first H2 but after H1
            currentSection = {
                id: slugify(h1Title),
                title: h1Title,
                content: `# ${h1Title}\n${line}\n`,
                subheadings: [],
            };
        }
    }

    // Push the last section
    if (currentSection) {
        currentSection.subheadings = extractSubheadings(currentSection.content);
        sections.push(currentSection);
    }

    return sections;
};

function PreWithCopy({ children }: Readonly<{ children?: React.ReactNode }>) {
    const preRef = useRef<HTMLPreElement | null>(null);
    const [copied, setCopied] = useState(false);

    const doCopy = async () => {
        const txt = preRef.current?.innerText ?? "";
        try {
            await navigator.clipboard.writeText(txt);
            setCopied(true);
            setTimeout(() => setCopied(false), 1400);
        } catch (e) {
            try {
                // fallback prompt
                // @ts-ignore
                const win = window as any;
                win.prompt("Copy code:", txt);
            } catch (_) {}
        }
    };

    return (
        <div className="relative group">
            <pre
                ref={preRef}
                className="bg-white/5 border border-white/10 p-2 sm:p-3 overflow-x-auto mb-4 mt-3 text-xs sm:text-sm max-w-full rounded-none"
            >
                {children}
            </pre>
            <button
                onClick={doCopy}
                className="absolute right-2 top-2 opacity-0 group-hover:opacity-100 transition-opacity duration-150 bg-white/6 hover:bg-white/10 text-white/90 text-xs px-2 py-1 rounded-none"
                aria-label="Copy code"
                title="Copy code"
            >
                {copied ? "Copied" : "Copy"}
            </button>
        </div>
    );
}

export default function MarkdownRenderer({
    content,
}: Readonly<{ content: string }>) {
    const [headings, setHeadings] = useState<TocItem[]>([]);
    const [sections, setSections] = useState<Section[]>([]);
    const [selectedSectionId, setSelectedSectionId] = useState<string>("");

    useEffect(() => {
        const toc = extractHeadings(content);
        setHeadings(toc);

        const pageSections = splitIntoSections(content);
        setSections(pageSections);

        // Set first section as default
        if (pageSections.length > 0) {
            setSelectedSectionId(pageSections[0].id);
        }
    }, [content]);

    const selectedSection = sections.find((s) => s.id === selectedSectionId);

    const markdownComponents = {
        h1: ({ children }: { children?: React.ReactNode }) => {
            const text =
                typeof children === "string"
                    ? children
                    : String(children || "");
            const id = slugify(text);
            return (
                <Serif
                    id={id}
                    className="text-3xl sm:text-4xl md:text-5xl font-bold text-white/90 mb-6 mt-8 first:mt-0 scroll-mt-8 break-words"
                >
                    {children}
                </Serif>
            );
        },
        h2: ({ children }: { children?: React.ReactNode }) => {
            const text =
                typeof children === "string"
                    ? children
                    : String(children || "");
            const id = slugify(text);
            return (
                <Serif
                    id={id}
                    className="text-2xl sm:text-3xl md:text-4xl font-bold text-white/85 mb-5 mt-7 border-b border-white/10 pb-2 scroll-mt-8 break-words"
                >
                    {children}
                </Serif>
            );
        },
        h3: ({ children }: { children?: React.ReactNode }) => {
            const text =
                typeof children === "string"
                    ? children
                    : String(children || "");
            const id = slugify(text);
            return (
                <Sans
                    id={id}
                    className="text-xl sm:text-2xl md:text-3xl font-semibold text-white/80 mb-4 mt-6 scroll-mt-8 break-words"
                >
                    {children}
                </Sans>
            );
        },
        h4: ({ children }: { children?: React.ReactNode }) => (
            <Sans className="text-lg sm:text-xl md:text-2xl font-semibold text-white/75 mb-3 mt-5 break-words">
                {children}
            </Sans>
        ),
        p: ({ children }: { children?: React.ReactNode }) => (
            <Sans className="text-white/70 text-sm sm:text-base md:text-lg leading-relaxed mb-4 break-words">
                {children}
            </Sans>
        ),
        a: ({
            href,
            children,
        }: {
            href?: string;
            children?: React.ReactNode;
        }) => (
            <a
                href={href}
                className="text-white/80 underline hover:text-white transition-colors duration-200 break-all"
                target="_blank"
                rel="noopener noreferrer"
            >
                {children}
            </a>
        ),
        ul: ({ children }: { children?: React.ReactNode }) => (
            <ul className="list-disc list-inside space-y-2 mb-4 text-white/70 break-words">
                {children}
            </ul>
        ),
        ol: ({ children }: { children?: React.ReactNode }) => (
            <ol className="list-decimal list-inside space-y-2 mb-4 text-white/70 break-words">
                {children}
            </ol>
        ),
        li: ({ children }: { children?: React.ReactNode }) => (
            <li className="text-sm sm:text-base md:text-lg leading-relaxed ml-4 break-words">
                {children}
            </li>
        ),
        code: ({
            inline,
            children,
        }: {
            inline?: boolean;
            children?: React.ReactNode;
        }) =>
            inline ? (
                <code className="bg-white/5 text-white/80 px-1.5 py-0.5 text-xs sm:text-sm font-mono border border-white/10 break-all">
                    {children}
                </code>
            ) : (
                <code className="text-white/80 font-mono text-xs sm:text-sm md:text-base break-all">
                    {children}
                </code>
            ),
        pre: ({ children }: { children?: React.ReactNode }) => {
            return <PreWithCopy>{children}</PreWithCopy>;
        },
        blockquote: ({ children }: { children?: React.ReactNode }) => (
            <blockquote className="border-l-4 border-white/20 pl-4 italic text-white/60 my-4 break-words">
                {children}
            </blockquote>
        ),
        table: ({ children }: { children?: React.ReactNode }) => (
            <div className="overflow-x-auto mb-6 -mx-4 sm:mx-0">
                <table className="min-w-full border border-white/10 text-sm">
                    {children}
                </table>
            </div>
        ),
        th: ({ children }: { children?: React.ReactNode }) => (
            <th className="border border-white/10 bg-white/5 px-2 sm:px-4 py-2 text-left font-semibold text-white/80 text-xs sm:text-sm">
                {children}
            </th>
        ),
        td: ({ children }: { children?: React.ReactNode }) => (
            <td className="border border-white/10 px-2 sm:px-4 py-2 text-white/70 text-xs sm:text-sm">
                {children}
            </td>
        ),
        hr: () => <hr className="border-white/10 my-8" />,
    };

    return (
        <div className="w-full">
            {/* Desktop: Layout with TOC sidebar */}
            <div className="hidden lg:flex lg:gap-12 max-w-7xl mx-auto px-8">
                {/* Desktop: Fixed Sidebar */}
                <aside className="w-52 shrink-0">
                    <div className="fixed top-8 w-52 max-h-[calc(100vh-4rem)] overflow-y-auto pr-3">
                        <div className="sticky top-0 bg-black -mt-2 py-2 z-10">
                            <Sans className="text-white/80 text-sm font-semibold mb-2 uppercase tracking-wide px-2">
                                Table of Contents
                            </Sans>
                            <div className="h-px bg-white/6 mx-2" />
                        </div>
                        <nav>
                            <ul className="space-y-1 pb-3 px-1 text-sm">
                                {headings.map((heading) => {
                                    const section = sections.find(
                                        (s) => s.id === heading.id
                                    );
                                    const isSelected =
                                        selectedSectionId === heading.id;
                                    return (
                                        <li key={heading.id}>
                                            <button
                                                onClick={() =>
                                                    setSelectedSectionId(
                                                        heading.id
                                                    )
                                                }
                                                style={{
                                                    paddingLeft: `${
                                                        (heading.level - 1) *
                                                        0.6
                                                    }rem`,
                                                }}
                                                className={`text-left block w-full text-sm px-2 py-1 rounded-none transition-colors duration-150 ${
                                                    isSelected
                                                        ? "bg-white/12 text-white font-medium"
                                                        : "text-white/60 hover:bg-white/6 hover:text-white"
                                                }`}
                                            >
                                                {heading.text}
                                            </button>

                                            {/* Show H3 subheadings only when this H2 is selected */}
                                            {isSelected &&
                                            section &&
                                            section.subheadings.length > 0 ? (
                                                <ul className="mt-2 space-y-1 ml-8 pl-2 border-l border-white/8 transition-all duration-200 ease-in-out overflow-hidden">
                                                    {section.subheadings.map(
                                                        (subheading) => (
                                                            <li
                                                                key={
                                                                    subheading.id
                                                                }
                                                            >
                                                                <button
                                                                    onClick={() => {
                                                                        const element =
                                                                            document.getElementById(
                                                                                subheading.id
                                                                            );
                                                                        if (
                                                                            element
                                                                        )
                                                                            element.scrollIntoView(
                                                                                {
                                                                                    behavior:
                                                                                        "smooth",
                                                                                    block: "start",
                                                                                }
                                                                            );
                                                                    }}
                                                                    className="text-left text-xs text-white/40 hover:text-white/70 transition-colors duration-150 w-full py-0.5 pl-2"
                                                                >
                                                                    {
                                                                        subheading.text
                                                                    }
                                                                </button>
                                                            </li>
                                                        )
                                                    )}
                                                </ul>
                                            ) : (
                                                <></>
                                            )}
                                        </li>
                                    );
                                })}
                            </ul>
                        </nav>
                    </div>
                </aside>

                {/* Desktop: Main Content */}
                <div className="flex-1 max-w-4xl prose prose-invert">
                    {selectedSection ? (
                        <ReactMarkdown
                            remarkPlugins={[remarkGfm]}
                            components={markdownComponents}
                        >
                            {selectedSection.content}
                        </ReactMarkdown>
                    ) : (
                        <></>
                    )}
                </div>
            </div>

            {/* Mobile: Content with dropdown selector */}
            <div className="lg:hidden px-4 sm:px-6 md:px-8 max-w-3xl mx-auto overflow-x-hidden">
                {/* Section Selector */}
                <div className="mb-6 sticky top-0 bg-black py-4 z-10">
                    <Sans className="text-white/90 text-xs font-semibold mb-2 uppercase tracking-wider">
                        Select Section
                    </Sans>
                    <select
                        value={selectedSectionId}
                        onChange={(e) => setSelectedSectionId(e.target.value)}
                        className="w-full bg-white/5 border border-white/10 text-white/80 px-3 py-2 text-sm"
                    >
                        {sections.map((section) => (
                            <option
                                key={section.id}
                                value={section.id}
                                className="bg-black"
                            >
                                {section.title}
                            </option>
                        ))}
                    </select>
                </div>

                {/* Content */}
                <div className="prose prose-invert prose-sm sm:prose-base max-w-none">
                    {selectedSection ? (
                        <ReactMarkdown
                            remarkPlugins={[remarkGfm]}
                            components={markdownComponents}
                        >
                            {selectedSection.content}
                        </ReactMarkdown>
                    ) : (
                        <></>
                    )}
                </div>
            </div>
        </div>
    );
}
