import { IconType } from "react-icons";
import { Sans } from "./Typograph";

export default function OutlinedButton({
    href,
    label,
    icon: Icon,
    showArrow = true,
    onPress,
    redirect,
    className: additionalClassName,
}: Readonly<{
    label: string;
    icon?: IconType;
    showArrow?: boolean;
    href?: string;
    redirect?: boolean;
    onPress?: () => void;
    className?: string;
}>) {
    const className = `group flex items-center gap-3 px-6 py-3 border border-white/20 hover:border-white/40 transition-all duration-300 hover:bg-white/5 cursor-pointer ${
        additionalClassName || ""
    }`;

    const content = (
        <>
            {Icon ? (
                <Icon className="text-white/70 group-hover:text-white transition-colors duration-300 text-lg" />
            ) : (
                <></>
            )}
            <Sans className="text-white/70 group-hover:text-white transition-colors duration-300 text-sm tracking-wider uppercase">
                {label}
            </Sans>
            {showArrow ? (
                <span className="text-white/50 group-hover:text-white/70 transition-colors duration-300">
                    →
                </span>
            ) : (
                <></>
            )}
        </>
    );

    if (href) {
        return (
            <a
                href={href}
                target="_blank"
                rel={redirect ? "noopener noreferrer" : ""}
                className={className}
            >
                {content}
            </a>
        );
    }

    return (
        <button onClick={onPress} className={className} type="button">
            {content}
        </button>
    );
}
