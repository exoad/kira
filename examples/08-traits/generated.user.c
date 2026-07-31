
typedef struct Dog Dog;
typedef struct Cat Cat;

struct Dog
{
    Str label;
};

Str Dog_speak(Dog* this)
{
    return "woof";
}

Str Dog_name(Dog* this)
{
    return this->label;
}

Int32 Dog_loudness(Dog* this)
{
    return 8;
}

simple Dog* Dog_new(Str label)
{
    Dog* self = (Dog*)kira_rc_alloc(sizeof(Dog));
    self->label = label;
    return self;
}

struct Cat
{
    Str label;
};

Str Cat_speak(Cat* this)
{
    return "meow";
}

Str Cat_name(Cat* this)
{
    return this->label;
}

simple Cat* Cat_new(Str label)
{
    Cat* self = (Cat*)kira_rc_alloc(sizeof(Cat));
    self->label = label;
    return self;
}

typedef struct Noisy Noisy;
typedef struct NoisyVTable NoisyVTable;
struct NoisyVTable
{
    Str (*speak)(void* self);
    Str (*name)(void* self);
    Int32 (*loudness)(void* self);
};

struct Noisy
{
    void* data;
    NoisyVTable* vtable;
};

typedef struct Speaker Speaker;
typedef struct SpeakerVTable SpeakerVTable;
struct SpeakerVTable
{
    Str (*speak)(void* self);
    Str (*name)(void* self);
};

struct Speaker
{
    void* data;
    SpeakerVTable* vtable;
};

Str Dog_speak(Dog* this);
Str Dog_name(Dog* this);
Int32 Dog_loudness(Dog* this);
Str Cat_speak(Cat* this);
Str Cat_name(Cat* this);
Void announce(Speaker s);
Int32 noiseLevel(Noisy s);
Dog* makeDog(Void);
Speaker makeSpeaker(Void);
Int32 main(Void);

static Str Noisy_speak_tramp_Dog(void* self) { return Dog_speak((Dog*)self); }
static Str Noisy_name_tramp_Dog(void* self) { return Dog_name((Dog*)self); }
static Int32 Noisy_loudness_tramp_Dog(void* self) { return Dog_loudness((Dog*)self); }
static NoisyVTable Noisy_vtable_Dog = { Noisy_speak_tramp_Dog, Noisy_name_tramp_Dog, Noisy_loudness_tramp_Dog };
static Str Speaker_speak_tramp_Dog(void* self) { return Dog_speak((Dog*)self); }
static Str Speaker_name_tramp_Dog(void* self) { return Dog_name((Dog*)self); }
static SpeakerVTable Speaker_vtable_Dog = { Speaker_speak_tramp_Dog, Speaker_name_tramp_Dog };
static Str Speaker_speak_tramp_Cat(void* self) { return Cat_speak((Cat*)self); }
static Str Speaker_name_tramp_Cat(void* self) { return Cat_name((Cat*)self); }
static SpeakerVTable Speaker_vtable_Cat = { Speaker_speak_tramp_Cat, Speaker_name_tramp_Cat };
/* module app:animals */
Void announce(Speaker s)
{
    print("%s\n", s.vtable->name(s.data));
    print("%s\n", s.vtable->speak(s.data));
}
Int32 noiseLevel(Noisy s)
{
    return s.vtable->loudness(s.data);
}
/* module app:main */
/* use app:animals */
Dog* makeDog(Void)
{
    Dog* dog = Dog_new("Rex");
    return dog;
}
Speaker makeSpeaker(Void)
{
    Cat* cat = Cat_new("Luna");
    return ((Speaker){ .data = cat, .vtable = &Speaker_vtable_Cat });
    kira_rc_release(cat);
}
Int32 main(Void)
{
    Dog* dog = makeDog();
    Cat* cat = Cat_new("Luna");
    announce(((Speaker){ .data = dog, .vtable = &Speaker_vtable_Dog }));
    announce(((Speaker){ .data = cat, .vtable = &Speaker_vtable_Cat }));
    Int32 dogLoud = noiseLevel(((Noisy){ .data = dog, .vtable = &Noisy_vtable_Dog }));
    print("%d\n", dogLoud);
    Speaker s = ((Speaker){ .data = dog, .vtable = &Speaker_vtable_Dog });
    print("%s\n", s.vtable->name(s.data));
    Speaker bolt = ((Speaker){ .data = Dog_new("Bolt"), .vtable = &Speaker_vtable_Dog });
    print("%s\n", bolt.vtable->speak(bolt.data));
    print("%s\n", makeSpeaker().vtable->name(makeSpeaker().data));
    kira_rc_release(dog);
    kira_rc_release(cat);
    return 0;
}
