import { useEffect, useState } from "react";
import Scaffold from "../components/Scaffold";
import MarkdownRenderer from "../components/MarkdownRenderer";
import { Sans } from "../components/Typograph";

const DOCS_URL =
    "https://raw.githubusercontent.com/exoad/kira/refs/heads/main/specifications/LanguageSpecifications.md";

export default function LanguageDocs() {
    const [markdown, setMarkdown] = useState<string>("");
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        fetch(DOCS_URL)
            .then((response) => {
                if (!response.ok) {
                    throw new Error("Failed to fetch documentation");
                }
                return response.text();
            })
            .then((text) => {
                setMarkdown(text);
                setLoading(false);
            })
            .catch((err) => {
                setError(err.message);
                setLoading(false);
            });
    }, []);

    return (
        <Scaffold>
            <div className="px-0 py-8 sm:py-12 overflow-x-hidden w-full">
                {loading ? (
                    <div className="flex items-center justify-center min-h-[50vh]">
                        <Sans className="text-white/50 text-lg animate-pulse">
                            Loading documentation...
                        </Sans>
                    </div>
                ) : (
                    <></>
                )}
                {error ? (
                    <div className="flex flex-col items-center justify-center min-h-[50vh] gap-4">
                        <Sans className="text-white/70 text-xl font-semibold">
                            Error loading documentation
                        </Sans>
                        <Sans className="text-white/50 text-base">{error}</Sans>
                    </div>
                ) : (
                    <></>
                )}
                {!loading && !error && markdown ? (
                    <MarkdownRenderer content={markdown} />
                ) : (
                    <></>
                )}
            </div>
        </Scaffold>
    );
}
