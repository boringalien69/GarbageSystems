package com.garbagesys.engine.strategies

import kotlin.math.*

/**
 * BayesianEngine implements the core prediction market math:
 * - LMSR pricing (Logarithmic Market Scoring Rule)
 * - Bayesian probability updating
 * - Kelly criterion for position sizing
 * - EV calculation
 *
 * All math is offline — no internet needed for calculations.
 */
object BayesianEngine {

    // ── LMSR Price Calculation ──
    /**
     * Calculate the price (implied probability) of outcome i given share quantities.
     * LMSR: price_i = exp(q_i / b) / sum(exp(q_j / b))
     */
    fun lmsrPrice(quantities: List<Double>, b: Double = 100.0): List<Double> {
        val exps = quantities.map { exp(it / b) }
        val total = exps.sum()
        return exps.map { it / total }
    }

    /**
     * Calculate LMSR cost to buy delta shares of outcome i.
     * C(q + delta) - C(q) where C(q) = b * ln(sum(exp(q_j/b)))
     */
    fun lmsrCost(quantities: List<Double>, outcomeIdx: Int, delta: Double, b: Double = 100.0): Double {
        val before = b * ln(quantities.sumOf { exp(it / b) })
        val after = quantities.toMutableList().also { it[outcomeIdx] += delta }
        val afterCost = b * ln(after.sumOf { exp(it / b) })
        return afterCost - before
    }

    // ── Bayesian Updating ──
    /**
     * Update probability estimate given new evidence.
     * P(H|E) = P(E|H) * P(H) / P(E)
     * Returns updated probability (posterior).
     *
     * @param prior P(H) — our prior belief
     * @param likelihoodIfTrue P(E|H) — how likely is this evidence if hypothesis is true
     * @param likelihoodIfFalse P(E|¬H) — how likely is this evidence if hypothesis is false
     */
    fun bayesianUpdate(
        prior: Double,
        likelihoodIfTrue: Double,
        likelihoodIfFalse: Double
    ): Double {
        val pEvidence = likelihoodIfTrue * prior + likelihoodIfFalse * (1 - prior)
        if (pEvidence == 0.0) return prior
        return (likelihoodIfTrue * prior) / pEvidence
    }

    /**
     * Update from multiple independent signals.
     */
    fun bayesianUpdateMultiple(
        prior: Double,
        signals: List<Pair<Double, Double>> // List of (likelihoodIfTrue, likelihoodIfFalse)
    ): Double {
        return signals.fold(prior) { prob, (lTrue, lFalse) ->
            bayesianUpdate(prob, lTrue, lFalse)
        }
    }

    // ── Kelly Criterion ──
    /**
     * Full Kelly fraction for a binary bet.
     * f = (p * (b+1) - 1) / b
     * where p = true probability, b = net odds (payout per unit)
     *
     * @param trueProbability our estimated true probability
     * @param marketPrice current market price (implied probability)
     * @param kellyFraction fractional Kelly (e.g. 0.25 = quarter Kelly, much safer)
     * @return fraction of bankroll to bet (0.0 if no edge)
     */
    fun kellyCriterion(
        trueProbability: Double,
        marketPrice: Double,
        kellyFraction: Double = 0.25
    ): Double {
        if (marketPrice <= 0.0 || marketPrice >= 1.0) return 0.0
        // b = net odds = (1 - marketPrice) / marketPrice
        val b = (1.0 - marketPrice) / marketPrice
        val fullKelly = (trueProbability * (b + 1) - 1) / b
        return maxOf(0.0, fullKelly * kellyFraction)
    }

    /**
     * Calculate position size in USDC given Kelly fraction and bankroll.
     */
    fun calculatePositionSize(
        bankrollUsdc: Double,
        trueProbability: Double,
        marketPrice: Double,
        kellyFraction: Double = 0.25,
        maxPositionUsdc: Double = 10.0
    ): Double {
        val fraction = kellyCriterion(trueProbability, marketPrice, kellyFraction)
        val size = bankrollUsdc * fraction
        return minOf(size, maxPositionUsdc)
    }

    // ── Expected Value ──
    /**
     * Calculate expected value of a bet.
     * EV = p * profit - (1-p) * stake
     * For prediction markets: profit = stake * (1 - price) / price
     */
    fun expectedValue(
        stake: Double,
        trueProbability: Double,
        marketPrice: Double
    ): Double {
        if (marketPrice <= 0.0) return -stake
        val payout = stake / marketPrice // payout if correct
        val profit = payout - stake
        return trueProbability * profit - (1 - trueProbability) * stake
    }

    /**
     * Calculate edge: how much better our estimate is vs the market.
     * edge > 0 means we have an advantage.
     */
    fun calculateEdge(trueProbability: Double, marketPrice: Double): Double {
        return trueProbability - marketPrice
    }

    // ── Weather Probability from NOAA forecast ──
    /**
     * Convert a NOAA temperature probability distribution to Polymarket bucket probability.
     * @param forecastTempC the forecast center temperature in Celsius
     * @param forecastUncertainty standard deviation of the forecast (usually 2-4°C)
     * @param bucketLowC lower bound of the target bucket
     * @param bucketHighC upper bound of the target bucket
     */
    fun weatherBucketProbability(
        forecastTempC: Double,
        forecastUncertainty: Double,
        bucketLowC: Double,
        bucketHighC: Double
    ): Double {
        // Normal distribution approximation
        val zLow = (bucketLowC - forecastTempC) / forecastUncertainty
        val zHigh = (bucketHighC - forecastTempC) / forecastUncertainty
        return normalCDF(zHigh) - normalCDF(zLow)
    }

    // ── Normal CDF approximation (Horner's method) ──
    private fun normalCDF(z: Double): Double {
        if (z < -6.0) return 0.0
        if (z > 6.0) return 1.0
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val pdf = exp(-0.5 * z * z) / sqrt(2 * PI)
        val cdf = 1.0 - pdf * poly
        return if (z >= 0) cdf else 1.0 - cdf
    }

    // ── Price Gap Detection (for latency arb) ──
    /**
     * Check if there's an exploitable gap between CEX price and Polymarket implied price.
     * @param realPriceDeltaPct % price change on CEX in last N seconds
     * @param polyImpliedDeltaPct implied change in Polymarket probability
     * @param feePct round-trip fee on Polymarket (typically 0.01–0.02)
     * @return true if gap is exploitable
     */
    fun isLatencyArbOpportunity(
        realPriceDeltaPct: Double,
        polyImpliedDeltaPct: Double,
        feePct: Double = 0.02
    ): Boolean {
        val gap = abs(realPriceDeltaPct - polyImpliedDeltaPct)
        return gap > feePct * 2 // need 2x fee to be worth it
    }

    // ── Crowd Contra Signal ──
    /**
     * Detect when crowd is overconfident (80%+) and worth fading.
     * Returns probability that the overconfident market will be wrong.
     */
    fun crowdContraSignal(
        crowdProbability: Double,
        threshold: Double = 0.80,
        historicalAccuracyAt80Pct: Double = 0.72  // markets priced at 80% resolve YES ~72% of the time historically
    ): Double? {
        if (crowdProbability < threshold) return null
        // The crowd says 80%+, but historically resolves YES at ~72% when priced this high
        // So NO has ~28% chance vs market's implied ~20% = edge of ~8%
        return 1.0 - historicalAccuracyAt80Pct
    }
}
