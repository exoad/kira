

CC = gcc
CFLAGS = -Wall -Wextra -std=c11 -O2 -Iinclude
LDFLAGS = -lm

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

TARGET = $(BIN_DIR)/kiragen.exe
NATIVE_GEN = $(BIN_DIR)/native_gen.exe

all: $(TARGET) $(NATIVE_GEN)

$(OBJ_DIR):
	@if not exist $(OBJ_DIR) mkdir $(OBJ_DIR)

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.c | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(MAIN_OBJ): $(MAIN_SRC) | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(NATIVE_OBJ): $(NATIVE_SRC) | $(OBJ_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(TARGET): $(OBJS) $(MAIN_OBJ)
	$(CC) $(OBJS) $(MAIN_OBJ) -o $@ $(LDFLAGS)
	@echo Build complete: $(TARGET)

$(NATIVE_GEN): $(OBJS) $(NATIVE_OBJ)
	$(CC) $(OBJS) $(NATIVE_OBJ) -o $@ $(LDFLAGS)
	@echo Build complete: $(NATIVE_GEN)

clean:
	-@if exist $(OBJ_DIR) rmdir /s /q $(OBJ_DIR) 2>nul
	-@if exist $(TARGET) del /q $(TARGET) 2>nul
	-@if exist $(NATIVE_GEN) del /q $(NATIVE_GEN) 2>nul
	-@if exist fibonacci.kir del /q fibonacci.kir 2>nul
	-@if exist fibonacci.asm del /q fibonacci.asm 2>nul
	-@if exist arc_example.asm del /q arc_example.asm 2>nul
	@echo Clean complete

run: $(TARGET)
	@echo.
	@echo === Running Kira VM ===
	@$(TARGET)

native: $(NATIVE_GEN)
	@echo.
	@echo === Running Native Code Generator ===
	@$(NATIVE_GEN)

.PHONY: all clean run native
