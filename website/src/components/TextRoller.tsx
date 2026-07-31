import { useEffect, useState } from "react";

export default function TextRoller({
    prefix,
    words,
    className = "",
}: Readonly<{
    prefix: string;
    words: string[];
    className?: string;
}>) {
    const [currentIndex, setCurrentIndex] = useState(0);
    const [isAnimating, setIsAnimating] = useState(false);
    useEffect(() => {
        const interval = setInterval(() => {
            setIsAnimating(true);
            setTimeout(() => {
                setCurrentIndex((prev) => (prev + 1) % words.length);
                setIsAnimating(false);
            }, 500);
        }, 3000);

        return () => clearInterval(interval);
    }, [words.length]);
    return (
        <div
            className={`${className} flex items-baseline gap-2 justify-center lg:justify-end`}
        >
            <span>{prefix}</span>
            <span
                className="inline-block overflow-hidden relative text-center lg:text-right"
                style={{ height: "1.2em", verticalAlign: "baseline" }}
            >
                <span
                    className={`block transition-all duration-500 ease-in-out font-bold italic bg-gradient-to-r from-white via-white/90 to-white/60 bg-clip-text text-transparent ${
                        isAnimating
                            ? "-translate-y-full opacity-0"
                            : "translate-y-0 opacity-100"
                    }`}
                >
                    {words[currentIndex]}
                </span>
            </span>
        </div>
    );
}
