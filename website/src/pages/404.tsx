import { useNavigate } from "react-router-dom";
import { Column } from "../components/FlexLayouter";
import OutlinedButton from "../components/Button.tsx";
import { Serif, Sans } from "../components/Typograph";

export default function NotFound() {
    const navigate = useNavigate();
    return (
        <div className="bg-black min-h-screen flex items-center justify-center px-4 sm:px-8 md:px-16">
            <Column className="gap-6 text-center">
                <Serif className="font-lora text-6xl font-bold">404</Serif>
                <Sans className="text-white/70 md:text-xl">
                    Page not found.
                </Sans>
                <OutlinedButton
                    label="Go Back"
                    onPress={() => navigate("/kira")}
                />
            </Column>
        </div>
    );
}
