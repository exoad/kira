export const strings = {
    pages: {
        landing: {
            sampleCode: `
module "kira:demo.main"

pub class Vector2 {
    require pub x: Float32
    require pub y: Float32

    pub fx @_op_mul_(other: Vector2): Float32 {
        return (x * other.x) + (y * other.y)
    }
}

fx main(): Void {
    a: Vector2 = Vector2 { 2, 5 }
    b: Vector2 = Vector2 { x = 3, y = 4 }
    @_trace_(a * b)
}`,
            transpiledCode: {
                Python: `class Vector2:
    def __init__(self, x: float, y: float):
        self.x = x
        self.y = y

    def __mul__(self, other: 'Vector2') -> float:
        return (self.x * other.x)
                + (self.y * other.y)

def main() -> None:
    a = Vector2(2.0, 5.0)
    b = Vector2(x=3.0, y=4.0)
    result = a * b
    print(result)

if __name__ == "__main__":
    main()`,
                "C++": `#include <iostream>

class Vector2 {
public:
    float x;
    float y;

    Vector2(float x, float y) : x(x), y(y) {}

    float operator*(const Vector2& other) const {
        return (x * other.x)
                + (y * other.y);
    }
};

int main() {
    Vector2 a(2.0f, 5.0f);
    Vector2 b(3.0f, 4.0f);
    float result = a * b;
    std::cout << result << std::endl;
    return 0;
}`,
                Java: `public class Main {
    public static class Vector2 {
        public float x;
        public float y;

        public Vector2(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float multiply(Vector2 other) {
            return (this.x * other.x)
                    + (this.y * other.y);
        }
    }

    public static void main(String[] args) {
        Vector2 a = new Vector2(2.0f, 5.0f);
        Vector2 b = new Vector2(3.0f, 4.0f);
        float result = a.multiply(b);
        System.out.println(result);
    }
}`,
                Swift: `struct Vector2 {
    let x: Float
    let y: Float

    static func * (lhs: Vector2,
                   rhs: Vector2) -> Float {
        return (lhs.x * rhs.x) + (lhs.y * rhs.y)
    }
}

func main() {
    let a = Vector2(x: 2.0, y: 5.0)
    let b = Vector2(x: 3.0, y: 4.0)
    let result = a * b
    print(result)
}

main()`,
                Dart: `class Vector2 {
  final double x;
  final double y;

  Vector2(this.x, this.y);

  double operator *(Vector2 other) {
    return (x * other.x) + (y * other.y);
  }
}

void main() {
  var a = Vector2(2.0, 5.0);
  var b = Vector2(3.0, 4.0);
  var result = a * b;
  print(result);
}`,
            },
            tagLineRollerTexts: [
                "Object-Oriented",
                "Portable",
                "Elegant",
                "Modern",
                "Simple",
            ],
        },
    },
};
