package yeastmate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class JonkerVolgenantMatching {

	/*
	 * Implementation of linear sum assignment for rectangular cost matrices using a
	 * modified Jonker-Volgenant algorithm following
	 * 
	 * Crouse, David F. "On implementing 2D rectangular assignment algorithms." EEE
	 * Transactions on Aerospace and Electronic Systems 52.4 (2016): 1679-1696.
	 * 
	 * (same algorithm as currently, Dec. 2021, used by scipy)
	 */

	public static Map<Integer, Integer> linearSumAssignment(double[] cost, int M, int N) {
		// default: minimize
		return linearSumAssignment(cost, M, N, false);
	}

	/**
	 * compute optimal linear sum assignment of rows to columns
	 * 
	 * @param cost     cost matrix in row-major order
	 * @param M        number of rows of cost
	 * @param N        number of columns of cost
	 * @param maximize whether to find maximal cost matching instead of minimal cost
	 * @return map from assigned row indices to assigned column indices
	 */
	public static Map<Integer, Integer> linearSumAssignment(double[] cost, int M, int N, boolean maximize) {
		// copy cost matrix, as it will be modified

		double[] c;
		if (M > N)
			c = getColMajorCopy(cost, M, N);
		else
			c = cost.clone();

		// we want to maximize -> minimize negative of cost matrix
		if (maximize)
			for (int i = 0; i < cost.length; i++) {
				c[i] = -c[i];
			}

		// subtract min element to ensure non-negative cost matrix
		double min = Double.MAX_VALUE;
		for (int i = 0; i < c.length; i++) {
			min = Math.min(min, c[i]);
		}
		for (int i = 0; i < c.length; i++) {
			c[i] -= min;
		}

		int[] col4row = match(c, Math.min(M, N), Math.max(M, N));

		// result to map of rows -> columns assignments
		Map<Integer, Integer> result = new HashMap<>();
		for (int i = 0; i < col4row.length; i++) {
			if (M <= N) {
				result.put(i, col4row[i]);
			} else {
				result.put(col4row[i], i);
			}
		}
		return result;
	}

	private static class ShortestPathResult {
		public int sink;
		public double minVal;
	}

	private static int[] match(double[] C, int Nr, int Nc) {
		// Step 1: init
		double[] u = new double[Nr];
		double[] v = new double[Nc];

		int[] col4row = new int[Nr];
		int[] row4col = new int[Nc];
		Arrays.fill(col4row, -1);
		Arrays.fill(row4col, -1);

		int[] path = new int[Nc];

		for (int curRow = 0; curRow < Nr; curRow++) {

			// Step 2
			double[] shortestPathCosts = new double[Nc];
			Arrays.fill(shortestPathCosts, Double.MAX_VALUE);

			// "Sets" of visited rows, columns as boolean flag arrays
			boolean[] SC = new boolean[Nc];
			boolean[] SR = new boolean[Nr];

			// Step 3
			ShortestPathResult r = findShortestAugmentingPath(C, curRow, SR, SC, Nc, u, v, shortestPathCosts, row4col,
					path);
			double minVal = r.minVal;
			int sink = r.sink;
			// Step 4
			updateDualVariables(Nr, Nc, u, v, curRow, minVal, SC, SR, shortestPathCosts, col4row);
			// Step 5
			augmentPreviousSolution(sink, curRow, path, col4row, row4col);
		}
		return col4row;
	}

	private static double[] getColMajorCopy(double[] m, int M, int N) {
		double[] m2 = new double[m.length];
		for (int i = 0; i < m2.length; i++) {
			// row, col from col-major index i
			int row = i % M;
			int col = i / M;
			int rowMajorIndex = row * N + col;
			m2[i] = m[rowMajorIndex];
		}
		return m2;
	}

	private static ShortestPathResult findShortestAugmentingPath(double[] C, int curRow, boolean[] SR, boolean[] SC,
			int Nc, double[] u, double[] v, double[] shortestPathCosts, int[] row4col, int[] path) {

		ShortestPathResult result = new ShortestPathResult();
		result.minVal = 0;
		result.sink = -1;
		int i = curRow;

		while (result.sink == -1) {
			SR[i] = true;
			for (int j = 0; j < Nc; j++) {
				int idx = i * Nc + j;
				double r = result.minVal + C[idx] - u[i] - v[j];
				if (r < shortestPathCosts[j] && !SC[j]) {
					path[j] = i;
					shortestPathCosts[j] = r;
				}
			}

			int j = 0;
			double tmpMin = Double.MAX_VALUE;
			for (int h = 0; h < Nc; h++) {

				if (SC[h])
					continue;
				if (shortestPathCosts[h] < tmpMin) {
					tmpMin = shortestPathCosts[h];
					j = h;
				}
			}

			SC[j] = true;
			result.minVal = shortestPathCosts[j];
			if (row4col[j] == -1)
				result.sink = j;
			else
				i = row4col[j];

		}

		return result;
	}

	private static void updateDualVariables(int Nr, int Nc, double[] u, double[] v, int curRow, double minVal,
			boolean[] SC, boolean[] SR, double[] shortestPathCosts, int[] col4row) {
		u[curRow] += minVal;
		for (int i = 0; i < Nr; i++) {
			if (SR[i] && i != curRow)
				u[i] += minVal - shortestPathCosts[col4row[i]];
		}
		for (int j = 0; j < Nc; j++) {
			if (SC[j])
				v[j] -= minVal - shortestPathCosts[j];
		}
	}

	private static void augmentPreviousSolution(int sink, int curRow, int[] path, int[] col4row, int[] row4col) {
		int j = sink;
		while (true) {
			int i = path[j];

			row4col[j] = i;

			int tmp = col4row[i];
			col4row[i] = j;
			j = tmp;

			if (i == curRow)
				break;
		}
	}

	public static void main(String[] args) {
		double[] c = new double[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
		int M = 4;
		int N = 3;
		Map<Integer, Integer> res = linearSumAssignment(c, M, N, true);

		res.forEach((k, v) -> {
			System.out.println("(" + k + "->" + v + ")");
		});

	}
}
