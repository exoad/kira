import Footer from "./Footer";
import { Link } from "react-router";
import logoImg from "../assets/logo.png";

export default function Scaffold({
    hideHeader,
    children,
}: Readonly<{ hideHeader?: any; children: React.ReactNode }>) {
    return (
        <div className="bg-black min-h-screen flex flex-col items-center justify-center ">
            {hideHeader ? (
                <></>
            ) : (
                <nav className="w-full py-4 flex items-center justify-center">
                    <Link to="/" aria-label="Home" className="inline-block">
                        <img
                            src={logoImg}
                            alt="MORI"
                            className="w-10 h-10 md:w-12 md:h-12"
                            draggable={false}
                        />
                    </Link>
                </nav>
            )}
            <div className="flex-1 w-full flex items-center justify-center">
                {children}
            </div>
            <Footer />
        </div>
    );
}
