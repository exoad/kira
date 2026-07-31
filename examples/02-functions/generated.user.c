
Str greeting(Void);
Int32 main(Void);
Str normalize(Str text);

/* module app:greetings */
/* use app:utils */
Str greeting(Void)
{
    return normalize("hello from functions");
}
/* module app:main */
/* use app:greetings */
Int32 main(Void)
{
    Str message = greeting();
    print("%s\n", message);
    return 0;
}
/* module app:utils */
Str normalize(Str text)
{
    return text;
}
