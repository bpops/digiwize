package com.digiwize;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.text.DefaultFormatter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Digiwize {
    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            Locale.setDefault(Locale.US);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
                // The default Swing look and feel is fine when the platform one is unavailable.
            }
            DigiwizeFrame frame = new DigiwizeFrame();
            frame.setVisible(true);
        });
    }

    private Digiwize() {
    }

    private static void runSelfTest() {
        AxisPoint x1 = new AxisPoint(new Point2D.Double(10, 100), 0);
        AxisPoint x2 = new AxisPoint(new Point2D.Double(110, 100), 10);
        AxisPoint y1 = new AxisPoint(new Point2D.Double(10, 100), 0);
        AxisPoint y2 = new AxisPoint(new Point2D.Double(10, 0), 10);
        Calibration linear = Calibration.from(x1, x2, y1, y2, false, false);
        assertNear(linear.toDataPoint(new Point2D.Double(60, 50)), 5, 5, "linear");

        AxisPoint logX1 = new AxisPoint(new Point2D.Double(10, 100), 1);
        AxisPoint logX2 = new AxisPoint(new Point2D.Double(110, 100), 100);
        Calibration logX = Calibration.from(logX1, logX2, y1, y2, true, false);
        assertNear(logX.toDataPoint(new Point2D.Double(60, 50)), 10, 5, "log x");

        if (AppIcon.images().size() != 5) {
            throw new IllegalStateException("app icon generation failed");
        }

        System.out.println("digiwize self-test passed");
    }

    private static void assertNear(DataPoint actual, double expectedX, double expectedY, String label) {
        if (Math.abs(actual.x() - expectedX) > 1.0e-9 || Math.abs(actual.y() - expectedY) > 1.0e-9) {
            throw new IllegalStateException(label + " calibration failed: got "
                    + actual.x() + "," + actual.y());
        }
    }
}

final class AppInfo {
    static final String NAME = "digiwize";
    static final String VERSION = "1.0";
    static final String ABOUT_HTML = "<html><body style='width: 380px'>"
            + "<h2 style='margin: 0 0 8px 0'>digiwize 1.0</h2>"
            + "<p>digiwize is a lightweight plot digitizer for turning graph images into reusable numeric data.</p>"
            + "<p>Drop in a plot image, calibrate two known points on each axis, then click the dataset to export x/y values. "
            + "It supports linear and log axes, ordered output, float or integer formatting, and quick clipboard copying.</p>"
            + "</body></html>";

    private AppInfo() {
    }
}

final class DigiwizeFrame extends JFrame {
    private static final String SEPARATE_OUTPUT_CARD = "separate";
    private static final String PAIRS_OUTPUT_CARD = "pairs";
    private static final String NOTEBOOK_OUTPUT_CARD = "notebook";
    private static final Color DEFAULT_DATA_POINT_COLOR = new Color(255, 213, 38);

    private final PlotImagePanel imagePanel = new PlotImagePanel(this::handleImageClick, this::hoverDataValueText);
    private final JTextArea xOutputArea = new JTextArea(7, 38);
    private final JTextArea yOutputArea = new JTextArea(7, 38);
    private final JTextArea pairsOutputArea = new JTextArea(7, 80);
    private final JTextArea notebookOutputArea = new JTextArea(7, 80);
    private final CardLayout outputCards = new CardLayout();
    private final JPanel outputCardPanel = new JPanel(outputCards);
    private final JComboBox<OutputMode> outputModeBox = new JComboBox<>(OutputMode.values());
    private final JComboBox<OutputOrder> outputOrderBox = new JComboBox<>(OutputOrder.values());
    private final JComboBox<NumericOutputType> numericOutputTypeBox = new JComboBox<>(NumericOutputType.values());
    private final JSpinner decimalPlacesSpinner = new JSpinner(new SpinnerNumberModel(3, 0, 9, 1));
    private final JLabel instructionLabel = new JLabel();
    private final JButton pointColorButton = new JButton("Point Color");
    private final JCheckBox logXBox = new JCheckBox("Log X");
    private final JCheckBox logYBox = new JCheckBox("Log Y");
    private Color dataPointColor = DEFAULT_DATA_POINT_COLOR;

    private Phase phase = Phase.NEEDS_IMAGE;
    private AxisPoint x1;
    private AxisPoint x2;
    private AxisPoint y1;
    private AxisPoint y2;
    private Calibration calibration;
    private final List<Point2D.Double> dataPixels = new ArrayList<>();
    private final List<DataPoint> dataValues = new ArrayList<>();

    DigiwizeFrame() {
        super(AppInfo.NAME);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(920, 680));
        setIconImages(AppIcon.images());
        setApplicationIcon();
        setLayout(new BorderLayout());

        add(createToolbar(), BorderLayout.NORTH);
        add(createMainContent(), BorderLayout.CENTER);
        installDropSupport();

        configureOutputArea(xOutputArea);
        configureOutputArea(yOutputArea);
        configureOutputArea(pairsOutputArea);
        configureOutputArea(notebookOutputArea);

        updateInstruction();
        pack();
        setLocationRelativeTo(null);
    }

    private void configureOutputArea(JTextArea textArea) {
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(0, 6));
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel topRow = new JPanel(new BorderLayout(10, 0));
        JLabel iconLabel = new JLabel(new ImageIcon(AppIcon.image(32)));
        iconLabel.setToolTipText("digiwize");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton resetCalibrationButton = new JButton("Reset Calibration");
        JButton undoButton = new JButton("Undo Point");
        JButton clearPointsButton = new JButton("Clear Points");
        JButton aboutButton = new JButton("About");

        Font toolbarButtonFont = resetCalibrationButton.getFont();
        pointColorButton.setFont(toolbarButtonFont.deriveFont(toolbarButtonFont.getSize2D() + 2f));
        resetCalibrationButton.addActionListener(event -> resetCalibration());
        undoButton.addActionListener(event -> undoLastPoint());
        clearPointsButton.addActionListener(event -> clearPoints());
        aboutButton.addActionListener(event -> showAbout());
        pointColorButton.addActionListener(event -> chooseDataPointColor());
        updatePointColorButton();

        logXBox.addActionListener(event -> validateLogMode(logXBox));
        logYBox.addActionListener(event -> validateLogMode(logYBox));

        buttons.add(resetCalibrationButton);
        buttons.add(undoButton);
        buttons.add(clearPointsButton);
        buttons.add(pointColorButton);
        buttons.add(logXBox);
        buttons.add(logYBox);
        buttons.add(aboutButton);

        instructionLabel.setFont(instructionLabel.getFont().deriveFont(Font.BOLD));
        topRow.add(iconLabel, BorderLayout.WEST);
        topRow.add(buttons, BorderLayout.CENTER);
        toolbar.add(topRow, BorderLayout.NORTH);
        toolbar.add(instructionLabel, BorderLayout.SOUTH);
        return toolbar;
    }

    private void setApplicationIcon() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(AppIcon.image(256));
                }
            }
        } catch (RuntimeException ignored) {
            // Some platforms only allow icon control from native application bundles.
        }
    }

    private void chooseDataPointColor() {
        Color selectedColor = JColorChooser.showDialog(this, "Choose Data Point Color", dataPointColor);
        if (selectedColor != null) {
            dataPointColor = selectedColor;
            imagePanel.setDataPointColor(dataPointColor);
            updatePointColorButton();
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this, new JLabel(AppInfo.ABOUT_HTML), "About " + AppInfo.NAME,
                JOptionPane.INFORMATION_MESSAGE, new ImageIcon(AppIcon.image(64)));
    }

    private void updatePointColorButton() {
        pointColorButton.setIcon(new ColorSwatchIcon(dataPointColor));
        pointColorButton.setBackground(null);
        pointColorButton.setForeground(null);
        pointColorButton.setOpaque(false);
    }

    private JSplitPane createMainContent() {
        JPanel outputPanel = new JPanel(new BorderLayout(0, 6));
        outputPanel.setBorder(BorderFactory.createTitledBorder("Digitized data"));

        JPanel outputControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton copyButton = new JButton("Copy Data");
        copyButton.addActionListener(event -> copyDataToClipboard());
        outputModeBox.setSelectedItem(OutputMode.SEPARATE_XY);
        outputModeBox.addActionListener(event -> updateOutputMode());
        outputOrderBox.setSelectedItem(OutputOrder.LOW_TO_HIGH);
        outputOrderBox.addActionListener(event -> refreshOutput());
        numericOutputTypeBox.setSelectedItem(NumericOutputType.FLOATS);
        numericOutputTypeBox.addActionListener(event -> {
            updateNumericOutputControls();
            refreshOutput();
        });
        configureDecimalPlacesSpinner();
        decimalPlacesSpinner.addChangeListener(event -> refreshOutput());
        outputControls.add(new JLabel("Output:"));
        outputControls.add(outputModeBox);
        outputControls.add(new JLabel("Order:"));
        outputControls.add(outputOrderBox);
        outputControls.add(new JLabel("Number:"));
        outputControls.add(numericOutputTypeBox);
        outputControls.add(new JLabel("Decimals:"));
        outputControls.add(decimalPlacesSpinner);
        outputControls.add(copyButton);

        JPanel separatePanel = new JPanel(new GridLayout(1, 2, 8, 0));
        JScrollPane xScroll = new JScrollPane(xOutputArea);
        JScrollPane yScroll = new JScrollPane(yOutputArea);
        xScroll.setBorder(BorderFactory.createTitledBorder("X values"));
        yScroll.setBorder(BorderFactory.createTitledBorder("Y values"));
        separatePanel.add(xScroll);
        separatePanel.add(yScroll);

        JScrollPane pairsScroll = new JScrollPane(pairsOutputArea);
        pairsScroll.setBorder(BorderFactory.createTitledBorder("Coordinate pairs: x,y"));

        JScrollPane notebookScroll = new JScrollPane(notebookOutputArea);
        notebookScroll.setBorder(BorderFactory.createTitledBorder("Python notebook"));

        outputCardPanel.add(separatePanel, SEPARATE_OUTPUT_CARD);
        outputCardPanel.add(pairsScroll, PAIRS_OUTPUT_CARD);
        outputCardPanel.add(notebookScroll, NOTEBOOK_OUTPUT_CARD);

        outputPanel.add(outputControls, BorderLayout.NORTH);
        outputPanel.add(outputCardPanel, BorderLayout.CENTER);
        updateOutputMode();
        updateNumericOutputControls();

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, imagePanel, outputPanel);
        splitPane.setResizeWeight(0.82);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    private void installDropSupport() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof File file) {
                        loadImage(file);
                        return true;
                    }
                } catch (Exception ex) {
                    showError("Could not import the dropped file.", ex);
                }
                return false;
            }
        };
        setTransferHandler(handler);
        imagePanel.setTransferHandler(handler);
    }

    private void loadImage(File file) {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                JOptionPane.showMessageDialog(this, "That file is not a supported image.", "Image Import",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            imagePanel.setImage(image, file.getName());
            clearCalibrationState();
            clearPoints();
            phase = Phase.CLICK_X1;
            updateInstruction();
        } catch (IOException ex) {
            showError("Could not read the image file.", ex);
        }
    }

    private void handleImageClick(Point2D.Double imagePoint) {
        if (imagePanel.getImage() == null) {
            return;
        }

        switch (phase) {
            case CLICK_X1 -> captureAxisPoint(imagePoint, Axis.X, 1);
            case CLICK_X2 -> captureAxisPoint(imagePoint, Axis.X, 2);
            case CLICK_Y1 -> captureAxisPoint(imagePoint, Axis.Y, 1);
            case CLICK_Y2 -> captureAxisPoint(imagePoint, Axis.Y, 2);
            case DIGITIZE -> captureDataPoint(imagePoint);
            case NEEDS_IMAGE -> {
            }
        }
    }

    private void captureAxisPoint(Point2D.Double imagePoint, Axis axis, int pointNumber) {
        Double value = askForAxisValue(axis, pointNumber);
        if (value == null) {
            return;
        }
        if (!axis.isValidForLog(value, isLogAxis(axis))) {
            JOptionPane.showMessageDialog(this, "Log axes require values greater than zero.", "Axis Value",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        AxisPoint axisPoint = new AxisPoint(imagePoint, value);
        if (axis == Axis.X && pointNumber == 1) {
            x1 = axisPoint;
            phase = Phase.CLICK_X2;
        } else if (axis == Axis.X) {
            if (sameTransformedAxisValue(x1.value(), value, Axis.X)) {
                JOptionPane.showMessageDialog(this, "The two x-axis values must be different.", "Axis Value",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            x2 = axisPoint;
            phase = Phase.CLICK_Y1;
        } else if (pointNumber == 1) {
            y1 = axisPoint;
            phase = Phase.CLICK_Y2;
        } else {
            if (sameTransformedAxisValue(y1.value(), value, Axis.Y)) {
                JOptionPane.showMessageDialog(this, "The two y-axis values must be different.", "Axis Value",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            y2 = axisPoint;
            finishCalibration();
        }

        imagePanel.setCalibrationPoints(x1, x2, y1, y2);
        updateInstruction();
    }

    private Double askForAxisValue(Axis axis, int pointNumber) {
        String axisName = axis == Axis.X ? "x" : "y";
        while (true) {
            String input = JOptionPane.showInputDialog(this,
                    "Enter the " + axisName + " value for " + axisName + "-axis point " + pointNumber + ":",
                    "Axis Calibration", JOptionPane.QUESTION_MESSAGE);
            if (input == null) {
                return null;
            }
            input = input.trim();
            if (input.isEmpty()) {
                continue;
            }
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Enter a numeric value.", "Axis Calibration",
                        JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void finishCalibration() {
        try {
            calibration = Calibration.from(x1, x2, y1, y2, logXBox.isSelected(), logYBox.isSelected());
            phase = Phase.DIGITIZE;
            recomputeDataValues();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Calibration", JOptionPane.WARNING_MESSAGE);
            clearCalibrationState();
            phase = Phase.CLICK_X1;
        }
    }

    private void captureDataPoint(Point2D.Double imagePoint) {
        if (calibration == null) {
            return;
        }
        try {
            DataPoint dataPoint = calibration.toDataPoint(imagePoint);
            dataPixels.add(imagePoint);
            dataValues.add(dataPoint);
            imagePanel.setDataPixels(dataPixels);
            refreshOutput();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Digitized Point", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void resetCalibration() {
        if (imagePanel.getImage() == null) {
            return;
        }
        clearCalibrationState();
        clearPoints();
        phase = Phase.CLICK_X1;
        updateInstruction();
    }

    private void clearCalibrationState() {
        x1 = null;
        x2 = null;
        y1 = null;
        y2 = null;
        calibration = null;
        imagePanel.setCalibrationPoints(null, null, null, null);
    }

    private void clearPoints() {
        dataPixels.clear();
        dataValues.clear();
        imagePanel.setDataPixels(dataPixels);
        refreshOutput();
    }

    private void undoLastPoint() {
        if (!dataPixels.isEmpty()) {
            dataPixels.remove(dataPixels.size() - 1);
            dataValues.remove(dataValues.size() - 1);
            imagePanel.setDataPixels(dataPixels);
            refreshOutput();
        }
    }

    private void copyDataToClipboard() {
        refreshOutput();
        StringSelection selection = new StringSelection(currentOutputText());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    private String currentOutputText() {
        OutputMode outputMode = (OutputMode) outputModeBox.getSelectedItem();
        if (outputMode == OutputMode.COORDINATE_PAIRS) {
            return pairsOutputArea.getText();
        }
        if (outputMode == OutputMode.PYTHON_NOTEBOOK) {
            return notebookOutputArea.getText();
        }
        return "x" + System.lineSeparator()
                + xOutputArea.getText()
                + System.lineSeparator()
                + System.lineSeparator()
                + "y" + System.lineSeparator()
                + yOutputArea.getText();
    }

    private String hoverDataValueText(Point2D.Double imagePoint) {
        if (phase != Phase.DIGITIZE || calibration == null) {
            return null;
        }
        DataPoint point = calibration.toDataPoint(imagePoint);
        return "x=" + formatDataValue(point.x()) + ", y=" + formatDataValue(point.y());
    }

    private void validateLogMode(JCheckBox changedBox) {
        if (calibration == null) {
            return;
        }
        try {
            calibration = Calibration.from(x1, x2, y1, y2, logXBox.isSelected(), logYBox.isSelected());
            recomputeDataValues();
        } catch (IllegalArgumentException ex) {
            changedBox.setSelected(!changedBox.isSelected());
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Log Axis", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void recomputeDataValues() {
        dataValues.clear();
        if (calibration != null) {
            for (Point2D.Double pixel : dataPixels) {
                dataValues.add(calibration.toDataPoint(pixel));
            }
        }
        refreshOutput();
    }

    private void refreshOutput() {
        StringBuilder xBuilder = new StringBuilder();
        StringBuilder yBuilder = new StringBuilder();
        StringBuilder pairsBuilder = new StringBuilder();
        String lineSeparator = System.lineSeparator();
        boolean logX = logXBox.isSelected();
        boolean logY = logYBox.isSelected();
        for (DataPoint point : orderedDataValues()) {
            if (xBuilder.length() > 0) {
                xBuilder.append(", ");
                yBuilder.append(", ");
                pairsBuilder.append(lineSeparator);
            }
            String formattedX = formatDataValue(point.x());
            String formattedY = formatDataValue(point.y());
            xBuilder.append(formattedX);
            yBuilder.append(formattedY);
            pairsBuilder.append(formattedX)
                    .append(',')
                    .append(formattedY);
        }
        xOutputArea.setText(xBuilder.toString());
        yOutputArea.setText(yBuilder.toString());
        pairsOutputArea.setText(pairsBuilder.toString());
        notebookOutputArea.setText(pythonNotebookOutput(xBuilder.toString(), yBuilder.toString(), logX, logY));
        xOutputArea.setCaretPosition(0);
        yOutputArea.setCaretPosition(0);
        pairsOutputArea.setCaretPosition(0);
        notebookOutputArea.setCaretPosition(0);
        imagePanel.repaint();
    }

    private String pythonNotebookOutput(String xValues, String yValues, boolean logX, boolean logY) {
        String lineSeparator = System.lineSeparator();
        List<String> notebookLines = new ArrayList<>(List.of(
                "import matplotlib.pyplot as plt",
                "%matplotlib inline",
                "",
                "x = [" + xValues + "]",
                "y = [" + yValues + "]",
                "",
                "plt.plot(x, y, marker=\"o\")",
                "plt.title(\"Data Extracted by digiwize\")",
                "plt.xlabel(\"x\")",
                "plt.ylabel(\"y\")"));
        if (logX) {
            notebookLines.add("plt.xscale(\"log\")");
        }
        if (logY) {
            notebookLines.add("plt.yscale(\"log\")");
        }
        notebookLines.add("plt.show()");
        return String.join(lineSeparator, notebookLines);
    }

    private List<DataPoint> orderedDataValues() {
        List<DataPoint> orderedValues = new ArrayList<>(dataValues);
        OutputOrder outputOrder = (OutputOrder) outputOrderBox.getSelectedItem();
        if (outputOrder == OutputOrder.LOW_TO_HIGH) {
            orderedValues.sort(Comparator.comparingDouble(DataPoint::x));
        }
        return orderedValues;
    }

    private String formatDataValue(double value) {
        NumericOutputType numericOutputType = (NumericOutputType) numericOutputTypeBox.getSelectedItem();
        if (numericOutputType == NumericOutputType.INTS) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.US, "%." + decimalPlaces() + "f", value);
    }

    private int decimalPlaces() {
        return ((Number) decimalPlacesSpinner.getValue()).intValue();
    }

    private void configureDecimalPlacesSpinner() {
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(decimalPlacesSpinner, "0");
        decimalPlacesSpinner.setEditor(editor);
        JFormattedTextField textField = editor.getTextField();
        textField.setColumns(2);
        if (textField.getFormatter() instanceof DefaultFormatter formatter) {
            formatter.setCommitsOnValidEdit(true);
        }
    }

    private void updateNumericOutputControls() {
        decimalPlacesSpinner.setEnabled(numericOutputTypeBox.getSelectedItem() == NumericOutputType.FLOATS);
        imagePanel.repaint();
    }

    private void updateOutputMode() {
        OutputMode outputMode = (OutputMode) outputModeBox.getSelectedItem();
        if (outputMode == OutputMode.COORDINATE_PAIRS) {
            outputCards.show(outputCardPanel, PAIRS_OUTPUT_CARD);
        } else if (outputMode == OutputMode.PYTHON_NOTEBOOK) {
            outputCards.show(outputCardPanel, NOTEBOOK_OUTPUT_CARD);
        } else {
            outputCards.show(outputCardPanel, SEPARATE_OUTPUT_CARD);
        }
    }

    private void updateInstruction() {
        instructionLabel.setText(switch (phase) {
            case NEEDS_IMAGE -> "Drop a plot image here.";
            case CLICK_X1 -> "Click the first known point on the x axis.";
            case CLICK_X2 -> "Click the second known point on the x axis.";
            case CLICK_Y1 -> "Click the first known point on the y axis.";
            case CLICK_Y2 -> "Click the second known point on the y axis.";
            case DIGITIZE -> "Click points along the dataset. Digitized x,y values appear below.";
        });
        imagePanel.setPhase(phase);
    }

    private boolean isLogAxis(Axis axis) {
        return axis == Axis.X ? logXBox.isSelected() : logYBox.isSelected();
    }

    private boolean sameTransformedAxisValue(double first, double second, Axis axis) {
        double transformedFirst = axis.transform(first, isLogAxis(axis));
        double transformedSecond = axis.transform(second, isLogAxis(axis));
        return Math.abs(transformedFirst - transformedSecond) < 1.0e-12;
    }

    private void showError(String message, Exception ex) {
        JOptionPane.showMessageDialog(this, message + System.lineSeparator() + ex.getMessage(),
                AppInfo.NAME, JOptionPane.ERROR_MESSAGE);
    }
}

final class PlotImagePanel extends JPanel {
    private static final DecimalFormat AXIS_LABEL_FORMAT = new DecimalFormat("0.############");
    private static final Color DEFAULT_DATA_POINT_COLOR = new Color(255, 213, 38);

    private final Consumer<Point2D.Double> clickConsumer;
    private final Function<Point2D.Double, String> hoverValueProvider;
    private BufferedImage image;
    private String imageName;
    private AxisPoint x1;
    private AxisPoint x2;
    private AxisPoint y1;
    private AxisPoint y2;
    private List<Point2D.Double> dataPixels = List.of();
    private Point2D.Double hoverImagePoint;
    private Color dataPointColor = DEFAULT_DATA_POINT_COLOR;
    private Phase phase = Phase.NEEDS_IMAGE;

    PlotImagePanel(Consumer<Point2D.Double> clickConsumer, Function<Point2D.Double, String> hoverValueProvider) {
        this.clickConsumer = Objects.requireNonNull(clickConsumer);
        this.hoverValueProvider = Objects.requireNonNull(hoverValueProvider);
        setBackground(new Color(245, 247, 250));
        setPreferredSize(new Dimension(920, 520));
        setBorder(BorderFactory.createLineBorder(new Color(195, 201, 210)));
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                Point2D.Double point = toImagePoint(event.getPoint());
                if (point != null) {
                    clickConsumer.accept(point);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent event) {
                hoverImagePoint = null;
                repaint();
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent event) {
                hoverImagePoint = toImagePoint(event.getPoint());
                repaint();
            }
        });
    }

    BufferedImage getImage() {
        return image;
    }

    void setImage(BufferedImage image, String imageName) {
        this.image = image;
        this.imageName = imageName;
        hoverImagePoint = null;
        repaint();
    }

    void setCalibrationPoints(AxisPoint x1, AxisPoint x2, AxisPoint y1, AxisPoint y2) {
        this.x1 = x1;
        this.x2 = x2;
        this.y1 = y1;
        this.y2 = y2;
        repaint();
    }

    void setDataPixels(List<Point2D.Double> dataPixels) {
        this.dataPixels = List.copyOf(dataPixels);
        repaint();
    }

    void setDataPointColor(Color dataPointColor) {
        this.dataPointColor = Objects.requireNonNull(dataPointColor);
        repaint();
    }

    void setPhase(Phase phase) {
        this.phase = phase;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (image == null) {
                paintEmptyState(g);
                return;
            }

            ImagePlacement placement = imagePlacement();
            g.drawImage(image, placement.x(), placement.y(), placement.width(), placement.height(), null);
            g.setColor(new Color(40, 45, 55));
            g.drawRect(placement.x(), placement.y(), placement.width(), placement.height());

            paintCalibration(g, placement);
            paintDataPoints(g, placement);
            paintHoverGuide(g, placement);
        } finally {
            g.dispose();
        }
    }

    private void paintEmptyState(Graphics2D g) {
        String title = "Drop a plot image here";
        String subtitle = "Then click two known x-axis points, two known y-axis points, and the dataset.";
        g.setColor(new Color(67, 77, 92));
        g.setFont(getFont().deriveFont(Font.BOLD, 24f));
        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, (getWidth() - titleWidth) / 2, getHeight() / 2 - 8);
        g.setFont(getFont().deriveFont(14f));
        g.setColor(new Color(95, 105, 120));
        int subtitleWidth = g.getFontMetrics().stringWidth(subtitle);
        g.drawString(subtitle, (getWidth() - subtitleWidth) / 2, getHeight() / 2 + 22);
    }

    private void paintCalibration(Graphics2D g, ImagePlacement placement) {
        g.setStroke(new BasicStroke(2f));
        if (x1 != null && x2 != null) {
            Point2D.Double first = toScreenPoint(x1.pixel(), placement);
            Point2D.Double second = toScreenPoint(x2.pixel(), placement);
            g.setColor(new Color(20, 98, 190));
            g.draw(new Line2D.Double(first, second));
        }
        if (y1 != null && y2 != null) {
            Point2D.Double first = toScreenPoint(y1.pixel(), placement);
            Point2D.Double second = toScreenPoint(y2.pixel(), placement);
            g.setColor(new Color(209, 101, 14));
            g.draw(new Line2D.Double(first, second));
        }
        paintAxisMarker(g, placement, x1, new Color(20, 98, 190));
        paintAxisMarker(g, placement, x2, new Color(20, 98, 190));
        paintAxisMarker(g, placement, y1, new Color(209, 101, 14));
        paintAxisMarker(g, placement, y2, new Color(209, 101, 14));
    }

    private void paintAxisMarker(Graphics2D g, ImagePlacement placement, AxisPoint axisPoint,
                                 Color color) {
        if (axisPoint == null) {
            return;
        }
        Point2D.Double screen = toScreenPoint(axisPoint.pixel(), placement);
        String label = AXIS_LABEL_FORMAT.format(axisPoint.value());
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(screen.x - 6, screen.y - 6, 12, 12));
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(screen.x - 6, screen.y - 6, 12, 12));
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        int padding = 4;
        int labelWidth = g.getFontMetrics().stringWidth(label) + padding * 2;
        int labelHeight = g.getFontMetrics().getHeight();
        int labelX = (int) Math.round(screen.x + 8);
        int labelY = (int) Math.round(screen.y - 8 - labelHeight);
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(labelX, labelY, labelWidth, labelHeight + padding, 6, 6);
        g.setColor(color);
        g.drawString(label, labelX + padding, labelY + g.getFontMetrics().getAscent() + 1);
    }

    private void paintDataPoints(Graphics2D g, ImagePlacement placement) {
        g.setFont(getFont().deriveFont(Font.BOLD, 11f));
        int index = 1;
        for (Point2D.Double imagePoint : dataPixels) {
            Point2D.Double screen = toScreenPoint(imagePoint, placement);
            g.setColor(dataPointColor);
            g.fill(new Ellipse2D.Double(screen.x - 4, screen.y - 4, 8, 8));
            g.setColor(new Color(42, 47, 55));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Ellipse2D.Double(screen.x - 4, screen.y - 4, 8, 8));
            g.setColor(contrastingTextColor(dataPointColor));
            g.drawString(Integer.toString(index), (float) screen.x + 6, (float) screen.y - 6);
            index++;
        }
    }

    private static Color contrastingTextColor(Color color) {
        double luminance = 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
        return luminance > 150 ? new Color(42, 47, 55) : Color.WHITE;
    }

    private void paintHoverGuide(Graphics2D g, ImagePlacement placement) {
        if (hoverImagePoint == null) {
            return;
        }
        Point2D.Double screen = toScreenPoint(hoverImagePoint, placement);
        g.setColor(new Color(70, 76, 86, 145));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 5f}, 0f));
        g.draw(new Line2D.Double(screen.x, placement.y(), screen.x, placement.y() + placement.height()));
        g.draw(new Line2D.Double(placement.x(), screen.y, placement.x() + placement.width(), screen.y));

        if (phase == Phase.DIGITIZE) {
            String hoverText = hoverValueProvider.apply(hoverImagePoint);
            if (hoverText != null) {
                paintHoverValueLabel(g, placement, screen, hoverText);
            }
        }
    }

    private void paintHoverValueLabel(Graphics2D g, ImagePlacement placement, Point2D.Double screen, String hoverText) {
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        int paddingX = 7;
        int paddingY = 5;
        int textWidth = g.getFontMetrics().stringWidth(hoverText);
        int textHeight = g.getFontMetrics().getHeight();
        int width = textWidth + paddingX * 2;
        int height = textHeight + paddingY * 2;
        int labelX = (int) Math.round(screen.x + 12);
        int labelY = (int) Math.round(screen.y + 12);
        int maxX = placement.x() + placement.width() - width - 4;
        int maxY = placement.y() + placement.height() - height - 4;
        labelX = Math.max(placement.x() + 4, Math.min(labelX, maxX));
        labelY = Math.max(placement.y() + 4, Math.min(labelY, maxY));

        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(labelX, labelY, width, height, 8, 8);
        g.setColor(new Color(70, 76, 86));
        g.drawRoundRect(labelX, labelY, width, height, 8, 8);
        g.drawString(hoverText, labelX + paddingX, labelY + paddingY + g.getFontMetrics().getAscent());
    }

    private Point2D.Double toImagePoint(java.awt.Point screenPoint) {
        if (image == null) {
            return null;
        }
        ImagePlacement placement = imagePlacement();
        if (screenPoint.x < placement.x() || screenPoint.x > placement.x() + placement.width()
                || screenPoint.y < placement.y() || screenPoint.y > placement.y() + placement.height()) {
            return null;
        }
        double imageX = (screenPoint.x - placement.x()) / placement.scale();
        double imageY = (screenPoint.y - placement.y()) / placement.scale();
        return new Point2D.Double(imageX, imageY);
    }

    private Point2D.Double toScreenPoint(Point2D.Double imagePoint, ImagePlacement placement) {
        return new Point2D.Double(
                placement.x() + imagePoint.x * placement.scale(),
                placement.y() + imagePoint.y * placement.scale());
    }

    private ImagePlacement imagePlacement() {
        int panelWidth = Math.max(1, getWidth());
        int panelHeight = Math.max(1, getHeight());
        int padding = 18;
        double scale = Math.min(
                (panelWidth - padding * 2) / (double) image.getWidth(),
                (panelHeight - padding * 2) / (double) image.getHeight());
        scale = Math.max(0.01, scale);
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int x = (panelWidth - width) / 2;
        int y = (panelHeight - height) / 2;
        return new ImagePlacement(x, y, width, height, scale);
    }
}

enum Phase {
    NEEDS_IMAGE,
    CLICK_X1,
    CLICK_X2,
    CLICK_Y1,
    CLICK_Y2,
    DIGITIZE
}

enum OutputMode {
    SEPARATE_XY("Separate X and Y"),
    COORDINATE_PAIRS("Coordinate pairs"),
    PYTHON_NOTEBOOK("Python notebook");

    private final String label;

    OutputMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

enum OutputOrder {
    LOW_TO_HIGH("Low to High"),
    ORDER_CLICKED("Order Clicked");

    private final String label;

    OutputOrder(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

enum NumericOutputType {
    FLOATS("Floats"),
    INTS("Ints");

    private final String label;

    NumericOutputType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

enum Axis {
    X,
    Y;

    double transform(double value, boolean logScale) {
        if (!isValidForLog(value, logScale)) {
            throw new IllegalArgumentException("Log axes require calibration and data values greater than zero.");
        }
        return logScale ? Math.log10(value) : value;
    }

    double inverse(double transformed, boolean logScale) {
        return logScale ? Math.pow(10.0, transformed) : transformed;
    }

    boolean isValidForLog(double value, boolean logScale) {
        return !logScale || value > 0.0;
    }
}

record AxisPoint(Point2D.Double pixel, double value) {
}

record DataPoint(double x, double y) {
}

record ImagePlacement(int x, int y, int width, int height, double scale) {
}

final class ColorSwatchIcon implements Icon {
    private final Color color;

    ColorSwatchIcon(Color color) {
        this.color = Objects.requireNonNull(color);
    }

    @Override
    public int getIconWidth() {
        return 16;
    }

    @Override
    public int getIconHeight() {
        return 16;
    }

    @Override
    public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fill(new Ellipse2D.Double(x + 2, y + 2, 12, 12));
            g.setColor(new Color(42, 47, 55));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Ellipse2D.Double(x + 2, y + 2, 12, 12));
        } finally {
            g.dispose();
        }
    }
}

final class AppIcon {
    private AppIcon() {
    }

    static List<Image> images() {
        return List.of(image(16), image(32), image(64), image(128), image(256));
    }

    static Image image(int size) {
        return draw(size);
    }

    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            double scale = size / 256.0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            RoundRectangle2D.Double background = new RoundRectangle2D.Double(
                    12 * scale, 12 * scale, 232 * scale, 232 * scale, 42 * scale, 42 * scale);
            g.setColor(new Color(248, 250, 252));
            g.fill(background);
            g.setColor(new Color(43, 52, 66));
            g.setStroke(new BasicStroke((float) Math.max(1.0, 6 * scale)));
            g.draw(background);

            g.setStroke(new BasicStroke((float) Math.max(0.5, 2 * scale)));
            g.setColor(new Color(199, 208, 220, 150));
            for (int i = 1; i < 4; i++) {
                double coordinate = (44 + i * 42) * scale;
                g.draw(new Line2D.Double(32 * scale, coordinate, 224 * scale, coordinate));
                g.draw(new Line2D.Double(coordinate, 32 * scale, coordinate, 224 * scale));
            }

            Path2D.Double curve = new Path2D.Double();
            for (int i = 0; i <= 96; i++) {
                double t = i / 96.0;
                double x = 34 + t * 188;
                double gaussian = Math.exp(-Math.pow((t - 0.52) / 0.22, 2.0) / 2.0);
                double y = 190 - gaussian * 112;
                if (i == 0) {
                    curve.moveTo(x * scale, y * scale);
                } else {
                    curve.lineTo(x * scale, y * scale);
                }
            }
            g.setColor(new Color(18, 104, 196));
            g.setStroke(new BasicStroke((float) Math.max(1.4, 9 * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(curve);

            double crossX = 96 * scale;
            double crossY = 110 * scale;
            double arm = 26 * scale;
            g.setColor(new Color(206, 52, 106));
            g.setStroke(new BasicStroke((float) Math.max(1.2, 7 * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(crossX - arm, crossY, crossX + arm, crossY));
            g.draw(new Line2D.Double(crossX, crossY - arm, crossX, crossY + arm));

            g.setColor(new Color(255, 255, 255, 220));
            g.fill(new Ellipse2D.Double(crossX - 6 * scale, crossY - 6 * scale, 12 * scale, 12 * scale));
        } finally {
            g.dispose();
        }
        return image;
    }
}

record Calibration(double xOriginValue, double yOriginValue, Point2D.Double origin,
                   Point2D.Double xBasis, Point2D.Double yBasis,
                   boolean logX, boolean logY) {
    static Calibration from(AxisPoint x1, AxisPoint x2, AxisPoint y1, AxisPoint y2,
                            boolean logX, boolean logY) {
        if (x1 == null || x2 == null || y1 == null || y2 == null) {
            throw new IllegalArgumentException("Calibration requires two x-axis points and two y-axis points.");
        }

        double tx1 = Axis.X.transform(x1.value(), logX);
        double tx2 = Axis.X.transform(x2.value(), logX);
        double ty1 = Axis.Y.transform(y1.value(), logY);
        double ty2 = Axis.Y.transform(y2.value(), logY);
        if (Math.abs(tx2 - tx1) < 1.0e-12 || Math.abs(ty2 - ty1) < 1.0e-12) {
            throw new IllegalArgumentException("Each axis needs two different calibration values.");
        }

        Point2D.Double origin = intersectLines(x1.pixel(), x2.pixel(), y1.pixel(), y2.pixel());
        Point2D.Double xBasis = divide(subtract(x2.pixel(), x1.pixel()), tx2 - tx1);
        Point2D.Double yBasis = divide(subtract(y2.pixel(), y1.pixel()), ty2 - ty1);
        double determinant = cross(xBasis, yBasis);
        if (Math.abs(determinant) < 1.0e-8) {
            throw new IllegalArgumentException("The calibrated x and y axes are too close to parallel.");
        }

        double xOffset = coefficientAlongBasis(subtract(x1.pixel(), origin), xBasis);
        double yOffset = coefficientAlongBasis(subtract(y1.pixel(), origin), yBasis);
        return new Calibration(tx1 - xOffset, ty1 - yOffset, origin, xBasis, yBasis, logX, logY);
    }

    DataPoint toDataPoint(Point2D.Double pixel) {
        Point2D.Double relative = subtract(pixel, origin);
        double determinant = cross(xBasis, yBasis);
        double xCoefficient = cross(relative, yBasis) / determinant;
        double yCoefficient = cross(xBasis, relative) / determinant;
        double transformedX = xOriginValue + xCoefficient;
        double transformedY = yOriginValue + yCoefficient;
        return new DataPoint(Axis.X.inverse(transformedX, logX), Axis.Y.inverse(transformedY, logY));
    }

    private static Point2D.Double intersectLines(Point2D.Double a1, Point2D.Double a2,
                                                 Point2D.Double b1, Point2D.Double b2) {
        Point2D.Double aDirection = subtract(a2, a1);
        Point2D.Double bDirection = subtract(b2, b1);
        double denominator = cross(aDirection, bDirection);
        if (Math.abs(denominator) < 1.0e-8) {
            throw new IllegalArgumentException("The calibrated x and y axis lines do not intersect cleanly.");
        }
        double t = cross(subtract(b1, a1), bDirection) / denominator;
        return new Point2D.Double(a1.x + t * aDirection.x, a1.y + t * aDirection.y);
    }

    private static Point2D.Double subtract(Point2D.Double first, Point2D.Double second) {
        return new Point2D.Double(first.x - second.x, first.y - second.y);
    }

    private static Point2D.Double divide(Point2D.Double point, double divisor) {
        return new Point2D.Double(point.x / divisor, point.y / divisor);
    }

    private static double cross(Point2D.Double first, Point2D.Double second) {
        return first.x * second.y - first.y * second.x;
    }

    private static double coefficientAlongBasis(Point2D.Double vector, Point2D.Double basis) {
        double basisLengthSquared = basis.x * basis.x + basis.y * basis.y;
        if (basisLengthSquared < 1.0e-12) {
            throw new IllegalArgumentException("Calibration points on each axis must be visually distinct.");
        }
        return (vector.x * basis.x + vector.y * basis.y) / basisLengthSquared;
    }
}
