CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -O2 -Iinclude -fno-pie
LDFLAGS = -lm -no-pie

SRC_DIR = src
OBJ_DIR = obj
INCLUDE_DIR = include
BIN_DIR = .

SRCS = $(SRC_DIR)/runtime.c $(SRC_DIR)/ir_impl.c $(SRC_DIR)/codegen.c
MAIN_SRC = kiragen.c
NATIVE_SRC = native_gen.c

OBJS = $(SRCS:$(SRC_DIR)/%.c=$(OBJ_DIR)/%.o)
MAIN_OBJ = $(OBJ_DIR)/kiragen.o
NATIVE_OBJ = $(OBJ_DIR)/native_gen.o

TARGET = $(BIN_DIR)/kiragen
NATIVE_GEN = $(BIN_DIR)/native_gen

all: $(TARGET) $(NATIVE_GEN)

$(OBJ_DIR):
	@mkdir -p $(OBJ_DIR)

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.c | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(MAIN_OBJ): $(MAIN_SRC) | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(NATIVE_OBJ): $(NATIVE_SRC) | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(TARGET): $(OBJS) $(MAIN_OBJ)
	$(CC) $^ -o $@ $(LDFLAGS)
	@echo Build complete: $(TARGET)

$(NATIVE_GEN): $(OBJS) $(NATIVE_OBJ)
	$(CC) $^ -o $@ $(LDFLAGS)
	@echo Build complete: $(NATIVE_GEN)

clean:
	@rm -rf $(OBJ_DIR)
	@rm -f $(TARGET) $(NATIVE_GEN)
	@rm -f fibonacci.kir fibonacci.asm arc_example.asm arc_example fibonacci
	@echo Clean complete

run: $(TARGET)
	@echo
	@echo === Running Kira VM ===
	@./$(TARGET)

native: $(NATIVE_GEN)
	@echo
	@./$(NATIVE_GEN)

.PHONY: all clean run native