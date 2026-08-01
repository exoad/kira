import { BrowserRouter, Routes, Route, useLocation } from "react-router";
import { useEffect, useState } from "react";
import Landing from "./pages/Landing";
import LanguageDocs from "./pages/LanguageDocs";
import NotFound from "./pages/404";

function AnimatedRoutes() {
    const location = useLocation();
    const [renderKey, setRenderKey] = useState(
        () => location.pathname + "|" + Date.now()
    );
    const [enter, setEnter] = useState(false);
    useEffect(() => {
        setRenderKey(location.pathname + "|" + Date.now());
        setEnter(false);
        const raf = requestAnimationFrame(() => setEnter(true));
        return () => cancelAnimationFrame(raf);
    }, [location.pathname]);
    return (
        <div className="relative w-full min-h-screen overflow-hidden">
            <div
                key={renderKey}
                className={`w-full h-full transform transition-all duration-400 ease-out ${
                    enter
                        ? "translate-y-0 scale-100 opacity-100"
                        : "translate-y-5 scale-95 opacity-0"
                }`}
            >
                <Routes location={location}>
                    <Route path="/" element={<Landing />} />
                    {/*specifically for github pages — exoad.github.io/kira/ */}
                    <Route path="/kira" element={<Landing />} />
                    <Route path="/kira/docs" element={<LanguageDocs />} />
                    <Route path="*" element={<NotFound />} />
                </Routes>
            </div>
        </div>
    );
}

export default function App() {
    return (
        <BrowserRouter>
            <AnimatedRoutes />
        </BrowserRouter>
    );
}
