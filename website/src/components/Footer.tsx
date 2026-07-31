import "../styles/Footer.css";
import { Column } from "./FlexLayouter.tsx";

export default function Footer() {
    return (
        <footer className="footer-component w-full text-white/70 text-sm font-raleway py-4">
            <Column
                className="md:flex-row"
                gap={2}
                mainAxisAlignment="spaceBetween"
                crossAxisAlignment="center"
            >
                <div className="w-full md:w-1/3 order-1 md:order-2 text-left text-xs">
                    <div className="font-lora font-bold">Kira</div>
                    <div className="pt-1">
                        © {new Date().getFullYear()} exoad. All rights reserved.
                    </div>
                </div>
                <div className="w-full md:w-1/3 md:text-right order-3 whitespace-nowrap text-right">
                    Source Code:{" "}
                    <a
                        href="https://github.com/exoad/kira"
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-white/80 hover:text-white transition-colors duration-300"
                    >
                        exoad/kira
                    </a>
                </div>
            </Column>
        </footer>
    );
}
