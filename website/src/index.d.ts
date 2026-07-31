export type Gender = "Male" | "Female";

export interface Profile {
    username: string;
    birthdate: Date;
    gender: Gender;
}

export interface Mortality {
    currentAge: number;
    expectedLifespan: number;
    yearsLeft: number;
    monthsLeft: number;
    weeksLeft: number;
    daysLeft: number;
    estimatedDeathDate: Date;
}