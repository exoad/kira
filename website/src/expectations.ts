export const expectations: {
    label: string;
    calc?: (secondsLeft: number) => number;
    check?: (deathDate: Date) => boolean;
    comment: string;
}[] = [
        {
            label: "🌬️ breaths left",
            calc: (s) => Math.floor(s / 5),
            comment: "assuming ~12 breaths per minute",
        },
        {
            label: "🍂 seasons left",
            calc: (s) => Math.floor(s / 86400 / 91.25),
            comment: "assuming ~91.25 days per season",
        },
        {
            label: "🌕 full moons left",
            calc: (s) => Math.floor(s / 2551442),
            comment: "assuming ~29.53 days per lunar cycle",
        },
        {
            label: "😴 hours of sleep left",
            calc: (s) => Math.floor((s / 86400) * 8),
            comment: "assuming 8 hours of sleep per day",
        },
        {
            label: "❤️ liters of blood your heart will pump",
            calc: (s) => Math.floor((s / 60) * 5),
            comment: "assuming ~5 liters per minute",
        },
        {
            label: "🌌 Will you see the next perihelion of Halley's Comet after 2061?",
            comment: "Expected again in 2134.",
            check: (d) => d.getFullYear() > 2134,
        },
        {
            label: "🎶 Will you live to see the 100th Grammy Awards?",
            comment: "Expected in 2058.",
            check: (d) => d.getFullYear() >= 2058,
        },
        {
            label: "☄️ Will you be here for the return of Halley's Comet?",
            comment: "Expected in July 2061.",
            check: (d) => d.getFullYear() > 2061,
        },
        {
            label: "🔮 Will you live to see the turn of the next century?",
            comment: "On January 1, 2100.",
            check: (d) => d.getFullYear() >= 2100,
        },
        {
            label: "🌐 Will you see the world population hit 10 billion?",
            comment: "Projected around 2065.",
            check: (d) => d.getFullYear() >= 2065,
        },
        {
            label: "🥳 weekends left",
            calc: (s) => Math.floor(s / 604800),
            comment: "assuming 7 days per week",
        },
        {
            label: "🪐 Will you be here for the 2080 Great Conjunction?",
            comment: "The next close alignment of Jupiter and Saturn.",
            check: (d) => d.getTime() > new Date("2080-03-15T00:00:00").getTime(),
        },
        {
            label: "🍽️ meals left",
            calc: (s) => Math.floor(s / 86400) * 3,
            comment: "assuming 3 meals per day",
        },
        {
            label: "💇 meters of hair left to grow",
            calc: (s) => Math.floor((s / 2628000) * 0.0125),
            comment: "assuming ~1.25 cm or 0.0125 m per month",
        },
        {
            label: "🔭 Will you see the next Transit of Venus?",
            comment: "A rare event, not happening again until 2117.",
            check: (d) => d.getFullYear() > 2117,
        },
        {
            label: "🌅 sunrises left",
            calc: (s) => Math.floor(s / 86400),
            comment: "1 sunrise per day",
        },
        {
            label: "🚀 Will you see the first human land on Mars?",
            comment: "NASA and SpaceX target mid-21st century.",
            check: (d) => d.getFullYear() >= 2050,
        },
        {
            label: "💓 heartbeats left",
            calc: (s) => Math.floor((s / 60) * 70),
            comment: "assuming ~70 beats per minute",
        },
        {
            label: "🇺🇸 Will you see the 300th anniversary of the United States?",
            comment: "On July 4, 2076.",
            check: (d) =>
                d.getTime() > new Date("2076-07-04T00:00:00").getTime(),
        },
        {
            label: "👀 blinks left",
            calc: (s) => Math.floor((s / 60) * 15),
            comment: "assuming ~15 blinks per minute",
        },
    ];
