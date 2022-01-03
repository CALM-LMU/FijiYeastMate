package yeastmate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class LabelTools {

	static double LARGE_NUMBER = 9000;
	public static Map<Integer, Integer> matchLabelsMinimizeDistance(Map<Pair<Integer, Integer>, Double> distances, double maxDistance) {

		final List<Integer> labels1 = new ArrayList<>(distances.keySet().stream().map(p -> p.getA()).collect(Collectors.toSet()));
		final List<Integer> labels2 = new ArrayList<>(distances.keySet().stream().map(p -> p.getB()).collect(Collectors.toSet()));
		final int m = labels1.size();
		final int n = labels2.size();

		final double[] cost = new double[m * n];
		for (int i=0; i<m; i++)
		{
			for (int j=0; j<n; j++)
			{
				final ValuePair<Integer, Integer> k = new ValuePair<>(labels1.get(i), labels2.get(j));
				// variable weights = distance
				// if we have large distance, multiply it with large constant to push optimization to assigning all feasible
				// pairs first
				double w =  maxDistance * LARGE_NUMBER;
				if (distances.containsKey(k)) {
					w = distances.get(k);
					if (w > maxDistance) {
						w = maxDistance * LARGE_NUMBER;
					}
				}
				cost[i*n + j] = w;
			}
		}

		Map<Integer, Integer> matchedIndices = JonkerVolgenantMatching.linearSumAssignment(cost, m, n);
		Map<Integer, Integer> matches = new HashMap<>();
		matchedIndices.forEach((row, column) -> {
			if (cost[row * n + column] < maxDistance)
				matches.put(labels1.get(row), labels2.get(column));
		});
		
		return matches;
	}
	
	public static Map<Integer, Integer> matchLabelsMaximizeIoU(Map<Pair<Integer, Integer>, Double> ious, double minIoU) {

		final List<Integer> labels1 = new ArrayList<>(ious.keySet().stream().map(p -> p.getA()).collect(Collectors.toSet()));
		final List<Integer> labels2 = new ArrayList<>(ious.keySet().stream().map(p -> p.getB()).collect(Collectors.toSet()));
		final int m = labels1.size();
		final int n = labels2.size();

		final double[] cost = new double[m * n];
		for (int i=0; i<m; i++)
		{
			for (int j=0; j<n; j++)
			{
				final ValuePair<Integer, Integer> k = new ValuePair<>(labels1.get(i), labels2.get(j));
				// if we have nonzero IoU between labels, use this as weight
				// if no overlap or below threshold -> weight == 0
				double w = 0.0;
				if (ious.containsKey(k)) {
					w = ious.get(k);
					if (w < minIoU) {
						w = 0.0;
					}
				}
				cost[i*n + j] = w;
			}
		}

		Map<Integer, Integer> matchedIndices = JonkerVolgenantMatching.linearSumAssignment(cost, m, n, true);
		Map<Integer, Integer> matches = new HashMap<>();
		matchedIndices.forEach((row, column) -> {
			if (cost[row * n + column] > minIoU)
				matches.put(labels1.get(row), labels2.get(column));
		});
		
		return matches;
	}
	
	public static <T extends IntegerType<T>> void relabelMap(RandomAccessibleInterval<T> img, Map<Integer, Integer> labelMapping)
	{
		Views.iterable(img).forEach((T x) -> {
			if (labelMapping.containsKey(x.getInteger())) {
				x.setInteger(labelMapping.get(x.getInteger()));
			}
		});
	}

	public static <T extends IntegerType<T>> void relabelFrom(RandomAccessibleInterval<T> img, int startValue)
	{

		LinkedHashSet<Integer> labelSet = getLabelSet(img);

		HashMap<Integer, Integer> labelsToNewLabels = new HashMap<>();
		AtomicInteger idx = new AtomicInteger(startValue);
		for (Integer s: labelSet) labelsToNewLabels.put(s, idx.incrementAndGet());

		Views.iterable(img).forEach((T x) -> {
			if (!(x.getInteger() == 0)) {
				x.setInteger(labelsToNewLabels.get(x.getInteger()));
			}
		});
	}

	public static <T extends IntegerType<T>> LinkedHashSet<Integer> getLabelSet(RandomAccessibleInterval<T> img) {
		LinkedHashSet<Integer> labelSet = new LinkedHashSet<>();
		Views.iterable(img).forEach((T x) -> {
			if (!(x.getInteger() == 0)) {
				labelSet.add(x.getInteger());
			}
		});
		return labelSet;
	}
	
	public static <T extends IntegerType<T>> void relabelFromOne(RandomAccessibleInterval<T> img)
	{
		relabelFrom(img, 0);
	}
	
	public static <T extends IntegerType<T>> Map<Pair<Integer, Integer>, Integer> getIntersections(
			RandomAccessibleInterval<T> img1, RandomAccessibleInterval<T> img2)
	{
		final int backgroundLabel = 0;
		Map<Pair<Integer, Integer>, Integer> intersections = new HashMap<>();
		
		Cursor<T> curImg1 = Views.iterable(img1).cursor();
		RandomAccess<T> raImg2 = img2.randomAccess();
		
		while(curImg1.hasNext())
		{
			curImg1.fwd();
			raImg2.setPosition(curImg1);
			
			final int label1 = curImg1.get().getInteger();
			final int label2 = raImg2.get().getInteger();
			
			if ((label1 == backgroundLabel) || (label2 == backgroundLabel))
			{
				continue;
			}
			
			ValuePair<Integer, Integer> labelPair = new ValuePair<>(label1, label2);
			if(!intersections.containsKey(labelPair)) {
				intersections.put(labelPair, 0);
			}
			intersections.put(labelPair, intersections.get(labelPair) + 1);
		}
		
		return intersections;
	}
	
	public static <T extends IntegerType<T>> Map<Pair<Integer, Integer>, Double> getDistances(
			RandomAccessibleInterval<T> img1, RandomAccessibleInterval<T> img2)
	{
		Map<Integer, double[]> centersOfMass1 = getCentersOfMass(img1);
		Map<Integer, double[]> centersOfMass2 = getCentersOfMass(img2);
		Map<Pair<Integer, Integer>, Double> distances = new HashMap<>();
		
		System.out.println("(" + centersOfMass1.size() + "," + centersOfMass2.size() + ")");
		
		for (Entry<Integer, double[]> com1 : centersOfMass1.entrySet())
		{
			for (Entry<Integer, double[]> com2 : centersOfMass2.entrySet())
			{
				double d = getEuclideanDistance(com1.getValue(), com2.getValue());
				ValuePair<Integer, Integer> labelPair = new ValuePair<>(com1.getKey(), com2.getKey());
				distances.put(labelPair, d);
			}
		}
		return distances;
	}
	
	public static double getEuclideanDistance(double[] v1, double[] v2)
	{
		if (v1.length != v2.length)
			return Double.NaN;
		double squareD = 0.0;
		for (int i = 0; i < v1.length; i++) {
			squareD += (v1[i] - v2[i])*(v1[i] - v2[i]);
		}
		return Math.sqrt(squareD);
	}
	
	public static <T extends IntegerType<T>> Map<Pair<Integer, Integer>, Double> getIoUs(
			RandomAccessibleInterval<T> img1, RandomAccessibleInterval<T> img2)
	{
		Map<Integer, Integer> areasImg1 = getAreas(img1);
		Map<Integer, Integer> areasImg2 = getAreas(img2);
		Map<Pair<Integer, Integer>, Integer> intersections = getIntersections(img1, img2);
		
		Map<Pair<Integer, Integer>, Double> ious = new HashMap<>();
		for (Pair<Integer, Integer> k: intersections.keySet())
		{
			final double a1 = areasImg1.get(k.getA());
			final double a2 = areasImg2.get(k.getB());
			final double i = intersections.get(k);
			final double iou = i / (a1 + a2 - i);
			ious.put(k, iou);
		}
		
		return ious;
	}
	
	public static <T extends IntegerType<T>> Map<Integer, Integer> getAreas(RandomAccessibleInterval<T> img)
	{
		final int backgroundLabel = 0;
		Map<Integer, Integer> areas = new HashMap<>();
		
		Views.iterable(img).forEach((T t) -> {
			final int label = t.getInteger();
			if (label == backgroundLabel) {
				return;
			}
			if(!areas.containsKey(label)) {
				areas.put(label, 0);
			}
			areas.put(label, areas.get(label) + 1);
		});
		
		return areas;
	}
	
	public static <T extends IntegerType<T>> Map<Integer, double[]> getCentersOfMass(RandomAccessibleInterval<T> img)
	{
		Map<Integer, double[]> centersOfMass = new HashMap<>();
		Map<Integer, Integer> areas = getAreas(img);
		final int numDimensions = img.numDimensions();
		
		final int backgroundLabel = 0;
		
		Cursor<T> cursor = Views.iterable(img).cursor();
		while (cursor.hasNext())
		{
			cursor.fwd();
			final int label = cursor.get().getInteger();
			if (label == backgroundLabel) {
				continue;
			}
			
			if(!centersOfMass.containsKey(label)) {
				centersOfMass.put(label, new double[numDimensions]);
			}
			for (int d = 0; d < numDimensions; d++) {
				centersOfMass.get(label)[d] += cursor.getDoublePosition(d);
			}
		}
		
		for (Integer k: centersOfMass.keySet())
		{
			for (int d = 0; d < numDimensions; d++) {
				centersOfMass.get(k)[d] /= areas.get(k);
			}
		}
		
		return centersOfMass;
	}
	
	public static void main(String[] args) {

		
		ImagePlus imp = IJ.openImage("E:\\yeastmate_dataset_benchmark\\gt_masks\\20190524_MIC60_test1_series1_mid.tif");
//		ImagePlus imp = IJ.openImage("/Users/david/Desktop/reproduction_hoerl/20190524_MIC60_test1_series1_mid_mask.tif");
		Img<UnsignedShortType> img = ImageJFunctions.wrapShort(imp);
		
		Img<UnsignedShortType> img2 = img.copy();
//		new ImageJ();
//		ImageJFunctions.show(img);
		relabelFrom(img2, 9000);
//		ImageJFunctions.show(img);

		Map<Pair<Integer, Integer>, Double> distances = getDistances(img, img2);
//		centersOfMass.values().forEach(c -> System.out.println(Util.printCoordinates(c)));
		
		Map<Pair<Integer, Integer>, Double> ioUs = getIoUs(img, img2);
		
		Map<Integer, Integer> match = matchLabelsMaximizeIoU(ioUs, 0.2);
//		Map<Integer, Integer> match = matchLabelsMinimizeDistance(distances, 100.0);
		match.forEach((x,y) -> {
			System.out.println(x + "->" + y);
		});

		// quick test: match should equal the relabelling result
		LinkedHashSet<Integer> labelSet = getLabelSet(img);
		HashMap<Integer, Integer> labelsToNewLabels = new HashMap<>();
		AtomicInteger idx = new AtomicInteger(9000);
		for (Integer s: labelSet) labelsToNewLabels.put(s, idx.incrementAndGet());

		System.out.println(labelsToNewLabels.equals(match));

	}

}
