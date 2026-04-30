# digiwize

digiwize is a small Java/Swing plot digitizer. Drop or open an image of a plot, calibrate two known points on the x axis, calibrate two known points on the y axis, then click points along the dataset. Digitized points are shown as comma-separated `x,y` rows in the text box at the bottom of the window.

![digiwize screenshot](https://github.com/bpops/digiwize/blob/main/screenshot.png)

## Run

```sh
javac -d out $(find src/main/java -name '*.java')
java -cp out com.digiwize.Digiwize
```

## Build and package

On macOS/Linux with zsh:

```sh
./scripts/build.zsh
java -jar dist/digiwize.jar
```

On Windows with PowerShell:

```powershell
.\scripts\build.ps1
java -jar .\dist\digiwize.jar
```

You can also run a calibration smoke test without opening the UI:

```sh
java -Djava.awt.headless=true -cp out com.digiwize.Digiwize --self-test
```

## Current workflow

1. Drag and drop a plot image into the app, or click `Open Image`.
2. Click two known locations on the x axis and enter their x values.
3. Click two known locations on the y axis and enter their y values.
4. Toggle `Log X` or `Log Y` before calibration for logarithmic axes.
5. Click dataset points; the bottom area updates with separate comma-separated x and y values by default.
6. Use the output dropdown to switch between separate x/y values and `x,y` coordinate pairs.
7. Use the order dropdown to list values by low-to-high x value or by click order.
8. Use the number dropdown to output floats or ints; floats can be shown with 0 to 9 decimal places.
