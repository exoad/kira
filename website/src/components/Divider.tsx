export default function Divider(props: Readonly<{ className?: string; }>) {
    return <div className={`w-[60%] h-px bg-white ${props.className ?? ""}`} />;
}