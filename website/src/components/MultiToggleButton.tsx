interface MultiToggleProps<T extends string> {
    options: readonly T[];
    value: T | null;
    onChange: (val: T) => void;
    className?: string;
}

export default function MultiToggle<T extends string>({
    options,
    value,
    onChange,
    className = "",
}: Readonly<MultiToggleProps<T>>) {
    return (
        <div
            className={`flex flex-wrap items-center justify-center gap-2 max-w-full ${className}`}
        >
            {options.map((opt) => {
                const selected = value === opt;
                return (
                    <button
                        key={opt}
                        type="button"
                        aria-pressed={selected}
                        onClick={() => onChange(opt)}
                        className={`px-3 py-2 text-sm font-semibold transition-colors duration-150 whitespace-nowrap ${
                            selected
                                ? "bg-white text-black"
                                : "bg-transparent text-white border-none"
                        }`}
                    >
                        {opt}
                    </button>
                );
            })}
        </div>
    );
}
