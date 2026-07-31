import { ReactNode } from "react";

type MainAxisAlignment =
    | "start"
    | "end"
    | "center"
    | "spaceBetween"
    | "spaceAround"
    | "spaceEvenly";

type CrossAxisAlignment = "start" | "end" | "center" | "stretch" | "baseline";

interface FlexLayouter {
    children: ReactNode;
    mainAxisAlignment?: MainAxisAlignment;
    crossAxisAlignment?: CrossAxisAlignment;
    gap?: number;
    className?: string;
}

const justifyMap: Record<MainAxisAlignment, string> = {
    start: "justify-start",
    end: "justify-end",
    center: "justify-center",
    spaceBetween: "justify-between",
    spaceAround: "justify-around",
    spaceEvenly: "justify-evenly",
};

const alignMap: Record<CrossAxisAlignment, string> = {
    start: "items-start",
    end: "items-end",
    center: "items-center",
    stretch: "items-stretch",
    baseline: "items-baseline",
};

function _findFlexClass(
    direction: "row" | "col",
    main: MainAxisAlignment,
    cross: CrossAxisAlignment,
    gap: number
) {
    return [
        "flex",
        direction === "row" ? "flex-row" : "flex-col",
        justifyMap[main],
        alignMap[cross],
        gap > 0 ? `gap-${gap}` : "",
    ]
        .filter(Boolean)
        .join(" ");
}

export function Row({
    children,
    mainAxisAlignment = "start",
    crossAxisAlignment = "center",
    gap = 0,
    className = "",
}: Readonly<FlexLayouter>) {
    return (
        <div
            className={`${_findFlexClass(
                "row",
                mainAxisAlignment,
                crossAxisAlignment,
                gap
            )} ${className}`}
        >
            {children ?? null}
        </div>
    );
}

export function Column({
    children,
    mainAxisAlignment = "start",
    crossAxisAlignment = "center",
    gap = 0,
    className = "",
}: Readonly<FlexLayouter>) {
    return (
        <div
            className={`${_findFlexClass(
                "col",
                mainAxisAlignment,
                crossAxisAlignment,
                gap
            )} ${className}`}
        >
            {children ?? null}
        </div>
    );
}
