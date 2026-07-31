import { Column } from "../components/FlexLayouter";
import { Sans, Serif } from "../components/Typograph";
import Scaffold from "../components/Scaffold";
import CodeBlock from "../components/CodeBlock";
import TextRoller from "../components/TextRoller";
import { strings } from "../data/strings";
import logoImg from "../assets/logo.png";
import OutlinedButton from "../components/Button";
import { useNavigate } from "react-router-dom";
import { useState, useRef } from "react";
import { SiPython, SiCplusplus, SiSwift, SiDart } from "react-icons/si";
import { FaJava } from "react-icons/fa";

type LanguageOption = "Kira" | "Python" | "C++" | "Java" | "Swift" | "Dart";

export default function Landing() {
    const navigate = useNavigate();
    const [selectedLanguage, setSelectedLanguage] =
        useState<LanguageOption>("Kira");

    const languages = [
        { name: "Python" as const, icon: SiPython },
        { name: "C++" as const, icon: SiCplusplus },
        { name: "Java" as const, icon: FaJava },
        { name: "Swift" as const, icon: SiSwift },
        { name: "Dart" as const, icon: SiDart },
    ];

    const getCode = () => {
        if (selectedLanguage === "Kira") {
            return strings.pages.landing.sampleCode;
        }

        const key =
            selectedLanguage as keyof typeof strings.pages.landing.transpiledCode;
        return strings.pages.landing.transpiledCode[key] ?? "";
    };

    const code = getCode();
    const selectorRef = useRef<HTMLDivElement | null>(null);

    const allLanguages = ["Kira", ...languages.map((l) => l.name)] as const;
    return (
        <Scaffold hideHeader>
            <div className="flex lg:flex-row flex-col items-center justify-center lg:justify-between gap-12 lg:gap-16 min-h-[80vh] transition-all duration-500 ease-in-out">
                {/* Left Side - Title and Info */}
                <Column className="items-center lg:items-start gap-6 min-w-0 transition-all duration-500 ease-in-out">
                    <div className="flex items-center gap-4 lg:gap-6 slide-in-right delay-100 opacity-0 flex-wrap">
                        <img
                            alt="Kira Logo"
                            src={logoImg}
                            className="w-16 h-16 md:w-20 md:h-20 lg:w-24 lg:h-24 opacity-80 hover:opacity-100 transition-opacity duration-300"
                            draggable={false}
                            loading="lazy"
                        />
                        <Serif className="text-6xl md:text-7xl lg:text-8xl font-black tracking-tight text-white">
                            Kira
                        </Serif>
                    </div>
                    <TextRoller
                        prefix="Simply"
                        words={strings.pages.landing.tagLineRollerTexts}
                        className="text-white/90 text-lg md:text-xl font-medium tracking-wide slide-in-right delay-200 opacity-0"
                    />
                    <Sans className="text-white/70 text-sm sm:text-base md:text-lg leading-relaxed w-full max-w-full sm:max-w-md text-center lg:text-left slide-in-right delay-300 opacity-0 md:px-0 px-4 whitespace-normal break-words">
                        Learn once. Ship anywhere. Write in a single modern
                        language — Kira — and transpile to the runtimes you care
                        about.
                    </Sans>
                    <div className="flex items-center gap-3 pt-2 slide-in-right delay-400 opacity-0">
                        <Sans className="text-white/40 text-sm uppercase tracking-widest whitespace-normal break-words">
                            In Development
                        </Sans>
                    </div>
                    <div className="flex flex-col lg:flex-row items-center gap-3 pt-4">
                        <OutlinedButton
                            label="View On GitHub"
                            href="https://github.com/exoad/kira"
                            className="slide-in-right delay-500 opacity-0 w-full sm:w-auto"
                            showArrow={false}
                        />
                        <button
                            onClick={() => navigate("/kira/docs")}
                            aria-label="Getting Started - View language docs"
                            className="px-6 py-3 bg-white text-black font-semibold text-sm ml-0 lg:ml-2 slide-in-right delay-600 opacity-0 rounded-none focus:ring-2 focus:ring-white/20 w-full sm:w-auto text-center"
                        >
                            <Sans className="text-black text-sm tracking-wider uppercase">
                                Getting Started
                            </Sans>
                        </button>
                    </div>
                </Column>

                <div className="flex flex-col items-center gap-6 transition-all duration-500 ease-in-out min-w-0 flex-1 w-full max-w-lg md:max-w-xl">
                    <div
                        key={selectedLanguage}
                        className="slide-in-bottom delay-200 opacity-0 code-transition w-full"
                    >
                        <div className="w-full">
                            <CodeBlock code={code} useAnimation={false} />
                        </div>
                    </div>

                    {/* Language Selector - Click to switch */}
                    <div className="flex flex-col items-center gap-4 slide-in-bottom delay-500 opacity-0">
                        <Sans className="text-white/50 text-xs uppercase tracking-wider">
                            Select target
                        </Sans>
                        <div
                            ref={selectorRef}
                            role="tablist"
                            aria-label="Language targets"
                            aria-orientation="horizontal"
                            tabIndex={0}
                            onKeyDown={(e) => {
                                if (
                                    e.key === "ArrowLeft" ||
                                    e.key === "ArrowRight"
                                ) {
                                    const curIndex =
                                        allLanguages.indexOf(selectedLanguage);
                                    const dir = e.key === "ArrowLeft" ? -1 : 1;
                                    const nextIndex =
                                        (curIndex + dir + allLanguages.length) %
                                        allLanguages.length;
                                    const next = allLanguages[nextIndex];
                                    setSelectedLanguage(next as LanguageOption);
                                    e.preventDefault();
                                }
                            }}
                            className="flex flex-wrap lg:flex-nowrap items-center gap-2 px-2 sm:px-0 w-full justify-center"
                        >
                            {/* Kira Button - Always first */}
                            <button
                                onClick={() => setSelectedLanguage("Kira")}
                                role="tab"
                                aria-selected={selectedLanguage === "Kira"}
                                className={`flex flex-col items-center gap-1 p-3 min-w-[56px] flex-shrink-0 transition-colors duration-150 ${
                                    selectedLanguage === "Kira"
                                        ? "bg-white text-black border border-white/30"
                                        : "hover:bg-white/6 text-white/75"
                                } rounded-none focus:ring-2 focus:ring-white/20`}
                                title="Kira (Original)"
                                aria-label="Select Kira as the source language"
                            >
                                <img
                                    src={logoImg}
                                    alt="Kira"
                                    className={`w-6 h-6 transition-all ${
                                        selectedLanguage === "Kira"
                                            ? "brightness-0"
                                            : "brightness-100"
                                    }`}
                                />
                                <Sans
                                    className={`text-xs tracking-wider uppercase ${
                                        selectedLanguage === "Kira"
                                            ? "text-black"
                                            : "text-white/60"
                                    }`}
                                >
                                    Kira
                                </Sans>
                            </button>

                            <div className="w-px h-8 bg-white/30"></div>

                            {/* Other Languages */}
                            {languages.map((lang) => {
                                const Icon = lang.icon;
                                return (
                                    <button
                                        key={lang.name}
                                        onClick={() =>
                                            setSelectedLanguage(lang.name)
                                        }
                                        role="tab"
                                        aria-selected={
                                            selectedLanguage === lang.name
                                        }
                                        aria-label={`Select ${lang.name} as target`}
                                        className={`flex flex-col items-center gap-1 p-3 min-w-[56px] flex-shrink-0 transition-colors duration-150 ${
                                            selectedLanguage === lang.name
                                                ? "bg-white text-black border border-white/30"
                                                : "hover:bg-white/6 text-white/75"
                                        } rounded-none focus:ring-2 focus:ring-white/20`}
                                        title={lang.name}
                                    >
                                        <Icon
                                            className={`w-6 h-6 transition-all ${
                                                selectedLanguage === lang.name
                                                    ? "text-black"
                                                    : "text-white"
                                            }`}
                                        />
                                        <Sans
                                            className={`text-xs tracking-wider uppercase ${
                                                selectedLanguage === lang.name
                                                    ? "text-black"
                                                    : "text-white/60"
                                            }`}
                                        >
                                            {lang.name}
                                        </Sans>
                                    </button>
                                );
                            })}
                        </div>
                    </div>
                </div>
            </div>
        </Scaffold>
    );
}
