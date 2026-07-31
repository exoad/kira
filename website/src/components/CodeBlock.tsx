import { Column } from "./FlexLayouter";
import { Mono, Sans } from "./Typograph";
import { useState } from "react";

const keywords = ["class", "pub", "mut", "return", "module", "fx", "require"];
const types = ["Int32", "Float32", "String", "Bool", "Void", "Vector2"];

function highlightSyntax(line: string, lineIndex: number) {
    const parts: Array<{
        text: string;
        type: "keyword" | "type" | "text";
        id: string;
    }> = [];
    let remaining = line;
    let position = 0;

    while (remaining.length > 0) {
        let matched = false;
        for (const keyword of keywords) {
            const regex = new RegExp(`^\\b${keyword}\\b`);
            if (regex.test(remaining)) {
                parts.push({
                    text: keyword,
                    type: "keyword",
                    id: `${lineIndex}-${position}-${keyword}`,
                });
                remaining = remaining.slice(keyword.length);
                position += keyword.length;
                matched = true;
                break;
            }
        }
        if (!matched) {
            parts.push({
                text: remaining[0],
                type: "text",
                id: `${lineIndex}-${position}-text`,
            });
            remaining = remaining.slice(1);
            position += 1;
        }
        for (const type of types) {
            const regex = new RegExp(`^\\b${type}\\b`);
            if (regex.test(remaining)) {
                parts.push({
                    text: type,
                    type: "type",
                    id: `${lineIndex}-${position}-${type}`,
                });
                remaining = remaining.slice(type.length);
                position += type.length;
                matched = true;
                break;
            }
        }
    }
    const mergedParts: typeof parts = [];
    for (const part of parts) {
        if (
            mergedParts.length > 0 &&
            mergedParts.at(-1)?.type === "text" &&
            part.type === "text"
        ) {
            mergedParts.at(-1)!.text += part.text;
        } else {
            mergedParts.push(part);
        }
    }
    return mergedParts.map((part) => {
        const className =
            part.type === "keyword"
                ? "text-white font-semibold"
                : part.type === "type"
                ? "text-white/90"
                : "text-white/70";
        return (
            <span key={part.id} className={className}>
                {part.text}
            </span>
        );
    });
}

export default function CodeBlock({
    code = "",
    filename,
    useAnimation,
}: Readonly<{
    code?: string;
    filename?: string;
    useAnimation?: any;
}>) {
    const [copied, setCopied] = useState(false);

    const copyCode = async () => {
        try {
            await navigator.clipboard.writeText(code);
            setCopied(true);
            setTimeout(() => setCopied(false), 1400);
        } catch (e) {
            console.error("Copy failed", e);
        }
    };
    return (
        <Column
            crossAxisAlignment="start"
            className="bg-black h-full overflow-hidden min-w-0 w-full px-4 sm:px-6"
        >
            {filename !== undefined && filename !== "." ? (
                <Sans className="text-white/40 text-xs mb-4 tracking-wider">
                    {filename || "sample.kira"}
                </Sans>
            ) : (
                <></>
            )}
            <div className="relative group">
                <button
                    aria-label="Copy code"
                    onClick={copyCode}
                    className={`absolute right-2 top-2 opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity duration-150 bg-white/6 hover:bg-white/10 text-white/90 text-xs px-2 py-1 rounded-none`}
                >
                    {copied ? "Copied" : "Copy"}
                </button>
                <Mono className="text-xs sm:text-sm md:text-base leading-relaxed block w-full min-w-0">
                    {code.split("\n").map((line, index) => (
                        <div
                            key={`line-${index}-${line.length}`}
                            className={`hover:bg-white/5 px-3 transition-colors break-words break-all whitespace-pre-wrap w-full min-w-0 ${
                                useAnimation ? "fade-in-line" : ""
                            }`}
                            style={{ animationDelay: `${0.4 + index * 0.12}s` }}
                        >
                            <span>{highlightSyntax(line, index)}</span>
                        </div>
                    ))}
                </Mono>
            </div>
        </Column>
    );
}
