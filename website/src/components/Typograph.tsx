export function Sans(
    props: Readonly<{ children: React.ReactNode; className?: string; id?: string }>
) {
    return (
        <div id={props.id} className={`font-raleway ${props.className ?? ""}`}>
            {props.children}
        </div>
    );
}

export function Serif(
    props: Readonly<{ children: React.ReactNode; className?: string; id?: string }>
) {
    return (
        <div id={props.id} className={`font-lora ${props.className ?? ""}`}>
            {props.children}
        </div>
    );
}

export function Mono(
    props: Readonly<{ children: React.ReactNode; className?: string }>
) {
    return (
        <div className={`font-fira ${props.className ?? ""}`}>
            {props.children}
        </div>
    );
}
