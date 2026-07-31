export function parseDate(birthdate: string): Date {
    const [year, month, day] = birthdate.split("-").map(Number);
    return new Date(year, month - 1, day);
}


export const mortalityStats = {
    female: 76,
    male: 71,
    source: "worldometer"
}