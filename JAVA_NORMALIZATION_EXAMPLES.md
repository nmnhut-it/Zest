# Java Code Normalization Examples

This document shows how the enhanced diff handles various Java code formatting differences.

## Brace Positioning

### Input Variations
```java
// Allman style (braces on new line)
public void method()
{
    if (condition)
    {
        doSomething();
    }
}

// K&R style (braces on same line)
public void method() {
    if (condition) {
        doSomething();
    }
}

// Mixed style
public void method()
{ if (condition) {
        doSomething();
    }
}
```

### After Normalization
All variations normalize to K&R style:
```java
public void method() {
    if (condition) {
        doSomething();
    }
}
```

## Array Declarations

### Input Variations
```java
String [] array1;      // Space before brackets
String[] array2;       // No space (preferred)
String []array3;       // Space before, none after
String[]array4;        // No spaces
int [] [] matrix;      // Multi-dimensional with spaces
```

### After Normalization
```java
String[] array1;
String[] array2;
String[]array3;        // Preserves no space after type
String[]array4;
int[][] matrix;
```

## Control Flow Statements

### Input Variations
```java
if(condition)          // No space after if
if (condition)         // Space after if (preferred)
while(true)            // No space
for(int i=0;i<10;i++) // No spaces
```

### After Normalization
```java
if (condition)
if (condition)
while (true)
for (int i=0;i<10;i++)  // Adds space after keyword only
```

## Empty Blocks

### Input Variations
```java
void empty1() { }      // Space in empty block
void empty2() {}       // No space (preferred)
void empty3() {
}                      // Newline in empty block
```

### After Normalization
```java
void empty1() {}
void empty2() {}
void empty3() {}
```

## Multiple Empty Lines

### Input Variations
```java
class Example {


    void method1() {}


    void method2() {}
}
```

### After Normalization
```java
class Example {

    void method1() {}

    void method2() {}
}
```
(Maximum of one empty line between elements)

## Semicolons

### Input Variations
```java
statement1() ;         // Space before semicolon
statement2();          // No space (preferred)
statement3();;         // Double semicolon
; }                    // Space before closing brace
```

### After Normalization
```java
statement1();
statement2();
statement3();
;}                     // No space before closing brace
```

## Complete Example

### Original Code (Various Styles)
```java
public class Calculator
{
    private int [] numbers ;
    
    public Calculator(int [] nums)
    {
        this.numbers = nums ;
    }
    
    public int sum()
    {
        int total = 0 ;
        for(int i = 0 ; i < numbers.length ; i++)
        {
            total += numbers[i] ;
        }
        return total ;
    }
}
```

### After Normalization
```java
public class Calculator {
    private int[] numbers;
    
    public Calculator(int[] nums) {
        this.numbers = nums;
    }
    
    public int sum() {
        int total = 0;
        for (int i = 0; i < numbers.length; i++) {
            total += numbers[i];
        }
        return total;
    }
}
```

## Benefits

1. **Style-agnostic diffing**: Focus on actual code changes, not formatting
2. **Cleaner diffs**: Brace style changes don't show as modifications
3. **Better word-level diff**: Consistent tokenization improves precision
4. **Reduced noise**: Whitespace and formatting variations are normalized away

## Notes

- Normalization is applied before diffing, not to the actual code
- The original formatting is preserved when accepting changes
- Only the diff visualization uses normalized code
- This helps identify real semantic changes vs cosmetic ones
