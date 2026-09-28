package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class ObjectiveDeliveryGameModelTest {
    @Test
    fun eachBusinessModelHasItsOwnFictionalScenario() {
        val models = ObjectiveDeliveryCompanyModel.values()

        assertEquals(3, models.size)
        assertEquals(3, models.map { it.key }.distinct().size)
        assertEquals(3, models.map { it.clientName }.distinct().size)
        assertTrue(models.all {
            it.referencePriceCents > 0 && it.clientBudgetCents > 0 && it.requestedDays > 0 &&
                it.directCostCents > 0 && it.fixedCostShareCents > 0 &&
                it.chapterTwoClientBudgetCents > it.directCostCents + it.fixedCostShareCents
        })
    }

    @Test
    fun onlyANewUntouchedCampaignCountsAsPristineForCloudRestore() {
        val pristine = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP)
        val inProgress = ObjectiveDeliveryGameRules.qualifyNeed(pristine, askedQuestions = true)

        assertTrue(ObjectiveDeliveryGameRules.isPristine(pristine))
        assertFalse(ObjectiveDeliveryGameRules.isPristine(inProgress))
        assertFalse(ObjectiveDeliveryGameRules.isPristine(pristine.copy(attemptNumber = 2)))
    }

    @Test
    fun completeNeedAndFeasibleOfferWinAndUnlockChapterTwo() {
        val start = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP)
        val qualified = ObjectiveDeliveryGameRules.qualifyNeed(start, askedQuestions = true)
        val priced = ObjectiveDeliveryGameRules.choosePrice(qualified, ObjectiveDeliveryPriceChoice.BALANCED)
        val timed = ObjectiveDeliveryGameRules.chooseTimeline(priced, ObjectiveDeliveryTimelineChoice.REQUESTED)

        val result = ObjectiveDeliveryGameRules.submitOffer(timed)

        assertEquals(ObjectiveDeliveryPhase.RESULT, result.phase)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, result.outcome)
        assertTrue(result.chapterOneWon)
        assertEquals(2, result.unlockedChapter)
    }

    @Test
    fun allThreeFictionalBusinessesAcceptTheirBalancedOnTimeOffer() {
        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val qualified = ObjectiveDeliveryGameRules.qualifyNeed(
                ObjectiveDeliveryCampaign(model),
                askedQuestions = true
            )
            val priced = ObjectiveDeliveryGameRules.choosePrice(
                qualified,
                ObjectiveDeliveryPriceChoice.BALANCED
            )
            val timed = ObjectiveDeliveryGameRules.chooseTimeline(
                priced,
                ObjectiveDeliveryTimelineChoice.REQUESTED
            )

            assertEquals(model.key, ObjectiveDeliveryOutcome.ORDER_ACCEPTED, ObjectiveDeliveryGameRules.evaluate(timed).outcome)
        }
    }

    @Test
    fun missingInformationRequestsRevisionAndDoesNotUnlockNextChapter() {
        val start = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.DISTRIBUTION)
        val skippedQuestions = ObjectiveDeliveryGameRules.qualifyNeed(start, askedQuestions = false)
        val priced = ObjectiveDeliveryGameRules.choosePrice(skippedQuestions, ObjectiveDeliveryPriceChoice.BALANCED)
        val timed = ObjectiveDeliveryGameRules.chooseTimeline(priced, ObjectiveDeliveryTimelineChoice.REQUESTED)

        val result = ObjectiveDeliveryGameRules.submitOffer(timed)

        assertEquals(ObjectiveDeliveryOutcome.CORRECTION_REQUESTED, result.outcome)
        assertFalse(result.chapterOneWon)
        assertEquals(1, result.unlockedChapter)
    }

    @Test
    fun twoMissedConditionsLoseTheOrderButReplayPreservesUnlockedProgress() {
        val start = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.SERVICES)
        val qualified = ObjectiveDeliveryGameRules.qualifyNeed(start, askedQuestions = true)
        val priced = ObjectiveDeliveryGameRules.choosePrice(qualified, ObjectiveDeliveryPriceChoice.AMBITIOUS)
        val timed = ObjectiveDeliveryGameRules.chooseTimeline(priced, ObjectiveDeliveryTimelineChoice.PRUDENT)
        val lost = ObjectiveDeliveryGameRules.submitOffer(timed)

        assertEquals(ObjectiveDeliveryOutcome.LOST, lost.outcome)
        assertEquals(1, lost.unlockedChapter)

        val wonBeforeReplay = lost.copy(chapterOneWon = true, unlockedChapter = 2)
        val replay = ObjectiveDeliveryGameRules.replayChapterOne(wonBeforeReplay)
        assertEquals(ObjectiveDeliveryPhase.QUALIFICATION, ObjectiveDeliveryGameRules.chapterOneState(replay).phase)
        assertEquals(ObjectiveDeliveryPhase.RESULT, replay.phase)
        assertEquals(2, replay.unlockedChapter)
        assertTrue(replay.chapterOneWon)
        assertEquals(2, replay.attemptNumber)
        assertEquals(1, replay.activeChapter)
        assertEquals(ObjectiveDeliveryOutcome.LOST, ObjectiveDeliveryGameRules.chapterOneState(wonBeforeReplay).outcome)
    }

    @Test
    fun chapterOneReplayIsSeparateAndReturningRestoresTheActiveChapterTwoBoard() {
        val won = completeChapterOne(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val inChapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(won)
        val quoteStarted = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            inChapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.BALANCED
        )
        val replay = ObjectiveDeliveryGameRules.replayChapterOne(quoteStarted)

        assertEquals(1, replay.activeChapter)
        assertEquals(2, replay.replaySession?.returnChapter)
        assertEquals(quoteStarted.chapterTwo, replay.chapterTwo)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, replay.outcome)
        assertEquals(ObjectiveDeliveryPhase.QUALIFICATION, ObjectiveDeliveryGameRules.chapterOneState(replay).phase)

        val restoredFromSave = ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(replay))
        assertEquals(replay, restoredFromSave)
        val returned = ObjectiveDeliveryGameRules.returnFromReplay(requireNotNull(restoredFromSave))

        assertEquals(2, returned.activeChapter)
        assertNull(returned.replaySession)
        assertEquals(quoteStarted.chapterTwo, returned.chapterTwo)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, returned.outcome)
    }

    @Test
    fun balancedQuoteAndClearExplanationWinChapterTwoForEveryFictionalBusiness() {
        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val chapterOne = completeChapterOne(ObjectiveDeliveryCampaign(model))
            val chapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(chapterOne)
            val priced = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
                chapterTwo,
                ObjectiveDeliveryChapterTwoPriceChoice.BALANCED
            )
            val timed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(
                priced,
                ObjectiveDeliveryTimelineChoice.REQUESTED
            )
            val negotiation = ObjectiveDeliveryGameRules.submitChapterTwoQuote(timed)
            val answered = ObjectiveDeliveryGameRules.chooseNegotiation(
                negotiation,
                ObjectiveDeliveryNegotiationChoice.EXPLAIN_VALUE
            )

            assertEquals(model.key, ObjectiveDeliveryOutcome.ORDER_ACCEPTED,
                ObjectiveDeliveryGameRules.evaluateChapterTwo(answered).outcome)
            assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED,
                ObjectiveDeliveryGameRules.submitChapterTwoNegotiation(answered).chapterTwo.outcome)
        }
    }

    @Test
    fun chapterTwoDiscountCanSaveBudgetButMustLeavePositiveMargin() {
        val chapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(
            completeChapterOne(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.DISTRIBUTION))
        )
        val quote = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            chapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.HIGH_MARGIN
        )
        val timed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(quote, ObjectiveDeliveryTimelineChoice.REQUESTED)
        val negotiation = ObjectiveDeliveryGameRules.submitChapterTwoQuote(timed)
        val discount = ObjectiveDeliveryGameRules.chooseNegotiation(
            negotiation,
            ObjectiveDeliveryNegotiationChoice.OFFER_DISCOUNT
        )
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterTwo(discount)

        assertTrue(evaluation.priceWithinBudget)
        assertTrue(evaluation.aboveCost)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, evaluation.outcome)

        val thinQuote = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            chapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.LOW_MARGIN
        )
        val thinTimed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(thinQuote, ObjectiveDeliveryTimelineChoice.REQUESTED)
        val thinNegotiation = ObjectiveDeliveryGameRules.chooseNegotiation(
            ObjectiveDeliveryGameRules.submitChapterTwoQuote(thinTimed),
            ObjectiveDeliveryNegotiationChoice.OFFER_DISCOUNT
        )
        assertFalse(ObjectiveDeliveryGameRules.evaluateChapterTwo(thinNegotiation).aboveCost)
        assertEquals(ObjectiveDeliveryOutcome.CORRECTION_REQUESTED,
            ObjectiveDeliveryGameRules.evaluateChapterTwo(thinNegotiation).outcome)
    }

    @Test
    fun chapterTwoCanExplainACombinedBudgetAndDeadlineMissAsALostSale() {
        val chapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(
            completeChapterOne(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.SERVICES))
        )
        val quote = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            chapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.HIGH_MARGIN
        )
        val timed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(quote, ObjectiveDeliveryTimelineChoice.PRUDENT)
        val negotiation = ObjectiveDeliveryGameRules.submitChapterTwoQuote(timed)
        val explained = ObjectiveDeliveryGameRules.chooseNegotiation(
            negotiation,
            ObjectiveDeliveryNegotiationChoice.EXPLAIN_VALUE
        )

        assertEquals(ObjectiveDeliveryOutcome.LOST, ObjectiveDeliveryGameRules.evaluateChapterTwo(explained).outcome)
    }

    @Test
    fun acceptedChapterTwoOrderUnlocksChapterThreeAndCompleteHandoffIsReady() {
        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val chapterTwo = completeChapterTwo(ObjectiveDeliveryCampaign(model))

            assertEquals(model.key, 3, chapterTwo.unlockedChapter)
            val chapterThree = ObjectiveDeliveryGameRules.continueToChapterThree(chapterTwo)
            val completeFile = ObjectiveDeliveryHandoffItem.values().fold(chapterThree) { state, item ->
                ObjectiveDeliveryGameRules.setHandoffItem(state, item, included = true)
            }
            val result = ObjectiveDeliveryGameRules.submitChapterThreeReview(completeFile)

            assertEquals(model.key, ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH,
                result.chapterThree.outcome)
            assertEquals(0, ObjectiveDeliveryGameRules.evaluateChapterThree(result).missingDocuments)
            assertEquals(4, result.unlockedChapter)
        }
    }

    @Test
    fun missingHandoffDetailsSuspendLaunchAndCanBeCompletedWithoutLosingConfirmedItems() {
        val chapterThree = ObjectiveDeliveryGameRules.continueToChapterThree(
            completeChapterTwo(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        )
        val partiallyReviewed = listOf(
            ObjectiveDeliveryHandoffItem.SIGNED_QUOTE,
            ObjectiveDeliveryHandoffItem.TECHNICAL_SPECIFICATION,
            ObjectiveDeliveryHandoffItem.CUSTOMER_OPTIONS,
            ObjectiveDeliveryHandoffItem.DELIVERY_CONDITIONS
        ).fold(chapterThree) { state, item ->
            ObjectiveDeliveryGameRules.setHandoffItem(state, item, included = true)
        }
        val clarification = ObjectiveDeliveryGameRules.submitChapterThreeReview(partiallyReviewed)

        assertEquals(ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION, clarification.chapterThree.outcome)
        assertEquals(1, ObjectiveDeliveryGameRules.evaluateChapterThree(clarification).missingDocuments)
        assertEquals(3, clarification.unlockedChapter)

        val review = ObjectiveDeliveryGameRules.correctChapterThreeReview(clarification)
        val completed = ObjectiveDeliveryGameRules.setHandoffItem(
            review,
            ObjectiveDeliveryHandoffItem.PROMISED_DATE,
            included = true
        )
        val ready = ObjectiveDeliveryGameRules.submitChapterThreeReview(completed)

        assertEquals(ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH, ready.chapterThree.outcome)
        assertTrue(ready.chapterThree.signedQuoteAttached)
        assertTrue(ready.chapterThree.deliveryConditionsConfirmed)
    }

    @Test
    fun chapterThreeReplayIsSavedSeparatelyAndOnlyReplacesTheBoardWhenKept() {
        val chapterThree = ObjectiveDeliveryGameRules.continueToChapterThree(
            completeChapterTwo(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.DISTRIBUTION))
        )
        val campaignResult = ObjectiveDeliveryHandoffItem.values().fold(chapterThree) { state, item ->
            ObjectiveDeliveryGameRules.setHandoffItem(state, item, included = true)
        }.let(ObjectiveDeliveryGameRules::submitChapterThreeReview)
        val replay = ObjectiveDeliveryGameRules.replayChapterThree(campaignResult)
        val partialReplay = ObjectiveDeliveryGameRules.setHandoffItem(
            replay,
            ObjectiveDeliveryHandoffItem.SIGNED_QUOTE,
            included = true
        )
        val blockedReplay = ObjectiveDeliveryGameRules.submitChapterThreeReview(partialReplay)

        assertEquals(campaignResult.chapterThree, blockedReplay.chapterThree)
        assertEquals(ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED,
            ObjectiveDeliveryGameRules.chapterThreeState(blockedReplay).outcome)

        val restored = ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(blockedReplay))
        assertEquals(blockedReplay, restored)
        val reviewReplay = ObjectiveDeliveryGameRules.correctChapterThreeReview(requireNotNull(restored))
        val allReplayItems = ObjectiveDeliveryHandoffItem.values().fold(reviewReplay) { state, item ->
            ObjectiveDeliveryGameRules.setHandoffItem(state, item, included = true)
        }
        val replayWin = ObjectiveDeliveryGameRules.submitChapterThreeReview(allReplayItems)
        val kept = ObjectiveDeliveryGameRules.keepChapterThreeReplayResult(replayWin)

        assertEquals(ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH, kept.chapterThree.outcome)
        assertNull(kept.replaySession)
        assertEquals(3, kept.activeChapter)
        assertEquals(4, kept.unlockedChapter)
    }

    @Test
    fun chapterFourBalancesLeaveCoverageTemporaryHiringAndAnnualRaiseEnvelope() {
        val start = ObjectiveDeliveryGameRules.continueToChapterFour(
            completeChapterThree(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        )
        val plan = planChapterFourWithApprovedLeave(start)
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterFour(plan)
        val result = ObjectiveDeliveryGameRules.submitChapterFourPlan(plan)

        assertTrue(evaluation.tasksCovered)
        assertTrue(evaluation.leaveCovered)
        assertTrue(evaluation.hiringDecisionCoherent)
        assertTrue(evaluation.annualReviewComplete)
        assertTrue(evaluation.raisesWithinEnvelope)
        assertEquals(ObjectiveDeliveryChapterFourOutcome.TEAM_READY, result.chapterFour.outcome)
        assertEquals(5, result.unlockedChapter)
    }

    @Test
    fun chapterFourOffersAnAlternativeLeaveDateAndLetsPlayerCorrectAnIncompletePlan() {
        var plan = ObjectiveDeliveryGameRules.continueToChapterFour(
            completeChapterThree(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.DISTRIBUTION))
        )
        plan = ObjectiveDeliveryGameRules.assignChapterFourTask(
            plan, ObjectiveDeliveryChapterFourTask.CUSTOMER_FILE, ObjectiveDeliveryTeamMember.ELISE
        )
        plan = ObjectiveDeliveryGameRules.assignChapterFourTask(
            plan, ObjectiveDeliveryChapterFourTask.PRODUCTION, ObjectiveDeliveryTeamMember.KARIM
        )
        plan = ObjectiveDeliveryGameRules.assignChapterFourTask(
            plan, ObjectiveDeliveryChapterFourTask.DISPATCH, ObjectiveDeliveryTeamMember.NOAH
        )
        plan = ObjectiveDeliveryGameRules.chooseChapterFourLeave(
            plan, ObjectiveDeliveryChapterFourLeaveChoice.OFFER_ALTERNATIVE_DATE
        )
        plan = ObjectiveDeliveryGameRules.chooseChapterFourHire(
            plan, ObjectiveDeliveryChapterFourHireDecision.DECLINE_WITH_ALTERNATIVE
        )
        val incomplete = ObjectiveDeliveryGameRules.submitChapterFourPlan(plan)
        assertEquals(ObjectiveDeliveryChapterFourOutcome.PLAN_NEEDS_REVIEW, incomplete.chapterFour.outcome)
        assertEquals(4, incomplete.unlockedChapter)

        val review = ObjectiveDeliveryGameRules.correctChapterFourPlan(incomplete)
        val completed = planChapterFourReview(review)
        val ready = ObjectiveDeliveryGameRules.submitChapterFourPlan(completed)
        assertEquals(ObjectiveDeliveryChapterFourOutcome.TEAM_READY, ready.chapterFour.outcome)
    }

    @Test
    fun chapterFourReplayPersistsSeparatelyFromTheCampaignBoard() {
        val complete = ObjectiveDeliveryGameRules.submitChapterFourPlan(
            planChapterFourWithApprovedLeave(
                ObjectiveDeliveryGameRules.continueToChapterFour(
                    completeChapterThree(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.SERVICES))
                )
            )
        )
        val replay = ObjectiveDeliveryGameRules.replayChapterFour(complete)
        val partial = ObjectiveDeliveryGameRules.assignChapterFourTask(
            replay, ObjectiveDeliveryChapterFourTask.CUSTOMER_FILE, ObjectiveDeliveryTeamMember.KARIM
        )
        val restored = ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(partial))

        assertEquals(partial, restored)
        assertEquals(complete.chapterFour, requireNotNull(restored).chapterFour)
        assertEquals(4, requireNotNull(restored).activeChapter)
        assertEquals(2, requireNotNull(restored).chapterFourAttemptNumber)
        assertEquals(ObjectiveDeliveryChapterFourPhase.PLAN,
            ObjectiveDeliveryGameRules.chapterFourState(requireNotNull(restored)).phase)
    }

    @Test
    fun chapterFiveUsesStockReservationsTransitAndSupplierTradeoffsForAllBusinessModels() {
        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val profile = model.chapterFiveProfile()
            val start = startChapterFive(model)
            val selected = ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    start, ObjectiveDeliveryChapterFiveSupplier.BACKUP
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
            val evaluation = ObjectiveDeliveryGameRules.evaluateChapterFive(selected)
            val result = ObjectiveDeliveryGameRules.submitChapterFivePlan(selected)

            assertTrue(model.key, profile.purchaseShortfallUnits > 0)
            assertEquals(model.key, profile.reservedUnits + profile.newOrderUnits -
                profile.stockOnHandUnits - profile.inTransitUnits, evaluation.neededUnits)
            assertTrue(model.key, evaluation.stockCoversDemand)
            assertTrue(model.key, evaluation.supplierOnTime)
            assertTrue(model.key, evaluation.purchaseWithinBudget)
            assertEquals(model.key, ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY, result.chapterFive.outcome)
            assertEquals(model.key, 6, result.unlockedChapter)
        }
    }

    @Test
    fun chapterFiveFindsLateExpensiveAndShortOrdersAndAllowsCorrection() {
        val start = startChapterFive(ObjectiveDeliveryCompanyModel.WORKSHOP)
        val late = ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
            ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(start, ObjectiveDeliveryChapterFiveSupplier.USUAL),
            ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
        )
        assertFalse(ObjectiveDeliveryGameRules.evaluateChapterFive(late).supplierOnTime)

        val expensive = ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
            ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(start, ObjectiveDeliveryChapterFiveSupplier.EXPRESS),
            ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
        )
        assertFalse(ObjectiveDeliveryGameRules.evaluateChapterFive(expensive).purchaseWithinBudget)

        val short = ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(start, ObjectiveDeliveryChapterFiveSupplier.BACKUP),
                ObjectiveDeliveryChapterFiveQuantityChoice.SHORT_BY_ONE
            )
        )
        assertEquals(ObjectiveDeliveryChapterFiveOutcome.PLAN_NEEDS_REVIEW, short.chapterFive.outcome)
        assertFalse(ObjectiveDeliveryGameRules.evaluateChapterFive(short).stockCoversDemand)

        val corrected = ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
            ObjectiveDeliveryGameRules.correctChapterFivePlan(short),
            ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
        )
        assertEquals(ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY,
            ObjectiveDeliveryGameRules.submitChapterFivePlan(corrected).chapterFive.outcome)
    }

    @Test
    fun chapterFiveReplaySavesSeparatelyAndCanBeDiscarded() {
        val campaignResult = ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    startChapterFive(ObjectiveDeliveryCompanyModel.DISTRIBUTION),
                    ObjectiveDeliveryChapterFiveSupplier.BACKUP
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
        )
        val replay = ObjectiveDeliveryGameRules.replayChapterFive(campaignResult)
        val replayInProgress = ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
            replay, ObjectiveDeliveryChapterFiveSupplier.USUAL
        )
        val restored = ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(replayInProgress))

        assertEquals(replayInProgress, restored)
        assertEquals(campaignResult.chapterFive, requireNotNull(restored).chapterFive)
        assertEquals(2, requireNotNull(restored).chapterFiveAttemptNumber)
        assertEquals(ObjectiveDeliveryChapterFivePhase.PROCUREMENT,
            ObjectiveDeliveryGameRules.chapterFiveState(requireNotNull(restored)).phase)
        assertEquals(5, ObjectiveDeliveryGameRules.returnFromReplay(requireNotNull(restored)).activeChapter)

        val replayAgain = ObjectiveDeliveryGameRules.replayChapterFive(
            ObjectiveDeliveryGameRules.returnFromReplay(requireNotNull(restored))
        )
        val replayWin = ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    replayAgain, ObjectiveDeliveryChapterFiveSupplier.BACKUP
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
        )
        val kept = ObjectiveDeliveryGameRules.keepChapterFiveReplayResult(replayWin)
        assertNull(kept.replaySession)
        assertEquals(5, kept.activeChapter)
        assertEquals(ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY, kept.chapterFive.outcome)

        val failedCampaignAttempt = ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    startChapterFive(ObjectiveDeliveryCompanyModel.WORKSHOP),
                    ObjectiveDeliveryChapterFiveSupplier.USUAL
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
        )
        val successfulReplay = ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    ObjectiveDeliveryGameRules.replayChapterFive(failedCampaignAttempt),
                    ObjectiveDeliveryChapterFiveSupplier.BACKUP
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
        )
        assertEquals(5, successfulReplay.unlockedChapter)
        assertEquals(successfulReplay, ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(successfulReplay)
        ))
        assertEquals(6, ObjectiveDeliveryGameRules.keepChapterFiveReplayResult(successfulReplay).unlockedChapter)
    }

    @Test
    fun chapterSixPlansWorkWithoutOvertimeAndRequiresACompleteQualityDisposition() {
        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val start = ObjectiveDeliveryGameRules.chooseChapterSixProductionPlan(
                startChapterSix(model), ObjectiveDeliveryChapterSixProductionPlan.PARALLEL_PREPARATION
            )
            val planned = ObjectiveDeliveryGameRules.submitChapterSixPlan(start)
            val sampled = ObjectiveDeliveryGameRules.submitChapterSixInspection(
                ObjectiveDeliveryGameRules.chooseChapterSixInspection(
                    planned, ObjectiveDeliveryChapterSixInspectionChoice.QUICK_SAMPLE
                )
            )
            assertEquals(model.key, ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE,
                sampled.chapterSix.outcome)
            assertEquals(model.key, 6, sampled.unlockedChapter)

            val fullInspection = ObjectiveDeliveryGameRules.submitChapterSixInspection(
                ObjectiveDeliveryGameRules.chooseChapterSixInspection(
                    ObjectiveDeliveryGameRules.retryChapterSixInspection(sampled),
                    ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST
                )
            )
            val result = ObjectiveDeliveryGameRules.submitChapterSixCorrection(
                ObjectiveDeliveryGameRules.chooseChapterSixCorrection(
                    fullInspection, ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK
                )
            )
            val evaluation = ObjectiveDeliveryGameRules.evaluateChapterSix(result)

            assertEquals(model.key, ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH, result.chapterSix.outcome)
            assertTrue(model.key, evaluation.defectDetected)
            assertTrue(model.key, evaluation.deadlineMet)
            assertEquals(model.key, model.chapterSixProfile().parallelProductionDays,
                evaluation.plannedProductionDays)
            assertEquals(model.key, 2, evaluation.correctionDays)
            assertEquals(model.key, model.chapterSixProfile().reworkCostCents, evaluation.correctionCostCents)
            assertEquals(model.key, 7, result.unlockedChapter)
        }
    }

    @Test
    fun chapterSixDocumentsCustomerDeviationOrKeepsUnsafeDispatchOnHold() {
        val planned = ObjectiveDeliveryGameRules.submitChapterSixPlan(
            ObjectiveDeliveryGameRules.chooseChapterSixProductionPlan(
                startChapterSix(ObjectiveDeliveryCompanyModel.WORKSHOP),
                ObjectiveDeliveryChapterSixProductionPlan.BALANCED_FLOW
            )
        )
        val correctionStage = ObjectiveDeliveryGameRules.submitChapterSixInspection(
            ObjectiveDeliveryGameRules.chooseChapterSixInspection(
                planned, ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST
            )
        )
        val held = ObjectiveDeliveryGameRules.submitChapterSixCorrection(
            ObjectiveDeliveryGameRules.chooseChapterSixCorrection(
                correctionStage, ObjectiveDeliveryChapterSixCorrectionChoice.DISPATCH_WITHOUT_CORRECTION
            )
        )

        assertEquals(ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD, held.chapterSix.outcome)
        assertEquals(6, held.unlockedChapter)

        val acceptedDeviation = ObjectiveDeliveryGameRules.submitChapterSixCorrection(
            ObjectiveDeliveryGameRules.chooseChapterSixCorrection(
                ObjectiveDeliveryGameRules.reopenChapterSixCorrection(held),
                ObjectiveDeliveryChapterSixCorrectionChoice.REQUEST_CUSTOMER_DEVIATION
            )
        )
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterSix(acceptedDeviation)
        assertEquals(ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION,
            acceptedDeviation.chapterSix.outcome)
        assertTrue(evaluation.dispositionApproved)
        assertEquals(0, evaluation.correctionCostCents)
        assertEquals(7, acceptedDeviation.unlockedChapter)
    }

    @Test
    fun chapterSixReplayIsSavedSeparatelyAndVersionFiveSavesMigrate() {
        val campaign = startChapterSix(ObjectiveDeliveryCompanyModel.DISTRIBUTION)
        val replay = ObjectiveDeliveryGameRules.chooseChapterSixProductionPlan(
            ObjectiveDeliveryGameRules.replayChapterSix(campaign),
            ObjectiveDeliveryChapterSixProductionPlan.PARALLEL_PREPARATION
        )
        val restoredReplay = requireNotNull(ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(replay)
        ))

        assertEquals(replay, restoredReplay)
        assertEquals(ObjectiveDeliveryChapterSixState(), restoredReplay.chapterSix)
        assertEquals(2, restoredReplay.chapterSixAttemptNumber)
        assertEquals(6, restoredReplay.unlockedChapter)
        assertEquals(6, ObjectiveDeliveryGameRules.returnFromReplay(restoredReplay).activeChapter)

        val replayWin = completeChapterSix(restoredReplay)
        assertEquals(6, replayWin.unlockedChapter)
        val kept = ObjectiveDeliveryGameRules.keepChapterSixReplayResult(replayWin)
        assertEquals(7, kept.unlockedChapter)
        assertEquals(6, kept.activeChapter)
        assertEquals(ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH, kept.chapterSix.outcome)

        val versionFive = JSONObject(ObjectiveDeliveryCampaignCodec.encode(
            completeChapterFive(ObjectiveDeliveryCompanyModel.WORKSHOP)
        )).apply {
            put("schemaVersion", 5)
            remove("chapterSixAttemptNumber")
            remove("chapterSix")
            remove("chapterSevenAttemptNumber")
            remove("chapterSeven")
            remove("chapterEightAttemptNumber")
            remove("chapterEight")
            remove("chapterNineAttemptNumber")
            remove("chapterNine")
            remove("chapterTenAttemptNumber")
            remove("chapterTen")
        }
        val migrated = ObjectiveDeliveryCampaignCodec.decode(versionFive.toString())
        assertEquals(6, migrated?.unlockedChapter)
        assertEquals(5, migrated?.activeChapter)
        assertEquals(ObjectiveDeliveryChapterSixState(), migrated?.chapterSix)
    }

    @Test
    fun chapterSixBalancedReworkShowsWhenThePromisedDateNeedsAnUpdate() {
        val prepared = completeChapterSix(startChapterSix(ObjectiveDeliveryCompanyModel.DISTRIBUTION),
            ObjectiveDeliveryChapterSixProductionPlan.BALANCED_FLOW)
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterSix(prepared)

        assertEquals(ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH, prepared.chapterSix.outcome)
        assertEquals(5, evaluation.estimatedFinishDays)
        assertEquals(4, evaluation.promisedDays)
        assertFalse(evaluation.deadlineMet)
        assertEquals(7, prepared.unlockedChapter)
    }

    @Test
    fun chapterSevenPhotoIsOptionalAndDoesNotReplaceTheOrderChecklist() {
        val start = startChapterSeven(ObjectiveDeliveryCompanyModel.WORKSHOP)
        val planned = ObjectiveDeliveryGameRules.submitChapterSevenDeliveryPlan(
            ObjectiveDeliveryGameRules.setChapterSevenPhotoSharing(
                ObjectiveDeliveryGameRules.chooseChapterSevenDelivery(
                    start, ObjectiveDeliveryChapterSevenDeliveryChoice.APPOINTMENT
                ),
                shared = true
            )
        )
        val incomplete = ObjectiveDeliveryGameRules.submitChapterSevenOrderCheck(
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(
                planned, items = true, quantities = true
            )
        )
        assertEquals(ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE,
            incomplete.chapterSeven.outcome)
        assertEquals(7, incomplete.unlockedChapter)
        assertTrue(ObjectiveDeliveryGameRules.evaluateChapterSeven(incomplete).photoShared)

        val readyForClaim = ObjectiveDeliveryGameRules.submitChapterSevenOrderCheck(
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(
                ObjectiveDeliveryGameRules.retryChapterSevenOrderCheck(incomplete),
                items = true, quantities = true, options = true, quality = true, documents = true
            )
        )
        assertEquals(ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM,
            readyForClaim.chapterSeven.phase)
        val resolved = ObjectiveDeliveryGameRules.submitChapterSevenClaimAction(
            ObjectiveDeliveryGameRules.chooseChapterSevenClaimAction(
                readyForClaim, ObjectiveDeliveryChapterSevenClaimAction.INVESTIGATE_AND_FOLLOW_UP
            )
        )
        assertEquals(ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED, resolved.chapterSeven.outcome)
        assertEquals(8, resolved.unlockedChapter)
        assertEquals(resolved, ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(resolved)
        ))
    }

    @Test
    fun chapterSevenReplayStaysSeparateAndVersionSixCampaignsMigrate() {
        val completed = completeChapterSeven(startChapterSeven(ObjectiveDeliveryCompanyModel.DISTRIBUTION))
        val replay = ObjectiveDeliveryGameRules.replayChapterSeven(completed)
        val restored = requireNotNull(ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(replay)
        ))
        assertEquals(replay, restored)
        assertEquals(completed.chapterSeven, restored.chapterSeven)
        assertEquals(ObjectiveDeliveryChapterSevenState(),
            ObjectiveDeliveryGameRules.chapterSevenState(restored))

        val replayWin = completeChapterSeven(restored)
        assertEquals(ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED,
            ObjectiveDeliveryGameRules.chapterSevenState(replayWin).outcome)
        val kept = ObjectiveDeliveryGameRules.keepChapterSevenReplayResult(replayWin)
        assertEquals(8, kept.unlockedChapter)
        assertEquals(7, kept.activeChapter)
        assertEquals(ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED, kept.chapterSeven.outcome)

        val versionSix = JSONObject(ObjectiveDeliveryCampaignCodec.encode(
            completeChapterSix(startChapterSix(ObjectiveDeliveryCompanyModel.WORKSHOP))
        )).apply {
            put("schemaVersion", 6)
            remove("chapterSevenAttemptNumber")
            remove("chapterSeven")
            remove("chapterEightAttemptNumber")
            remove("chapterEight")
            remove("chapterNineAttemptNumber")
            remove("chapterNine")
            remove("chapterTenAttemptNumber")
            remove("chapterTen")
        }
        val migrated = ObjectiveDeliveryCampaignCodec.decode(versionSix.toString())
        assertEquals(7, migrated?.unlockedChapter)
        assertEquals(6, migrated?.activeChapter)
        assertEquals(ObjectiveDeliveryChapterSevenState(), migrated?.chapterSeven)
    }

    @Test
    fun chapterEightBalancesWeeklyGoalsFairTreatmentAndProfitSharing() {
        val successful = completeChapterEight(startChapterEight(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterEight(successful)
        assertEquals(ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS, successful.chapterEight.outcome)
        assertTrue(evaluation.staffingMatchesWorkload)
        assertTrue(evaluation.absenceHandledFairly)
        assertTrue(evaluation.payrollWasCorrected)
        assertTrue(evaluation.conflictAddressed)
        assertTrue(evaluation.raisesWithinEnvelope)
        assertTrue(evaluation.profitShareConfirmed)
        assertEquals(7_840_000, evaluation.profitShareCents)
        assertEquals(9, successful.unlockedChapter)
        assertEquals(successful, ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(successful)
        ))

        var poorPlan = startChapterEight(ObjectiveDeliveryCompanyModel.WORKSHOP)
        poorPlan = ObjectiveDeliveryGameRules.submitChapterEightWeekPlan(
            ObjectiveDeliveryGameRules.chooseChapterEightOvertime(
                ObjectiveDeliveryGameRules.chooseChapterEightBonusCriteria(
                    ObjectiveDeliveryGameRules.chooseChapterEightStaffing(
                        poorPlan, ObjectiveDeliveryChapterEightStaffing.EXTRA_UNPLANNED_ELEVENTH
                    ),
                    ObjectiveDeliveryChapterEightBonusCriteria.VOLUME_ONLY
                ),
                ObjectiveDeliveryChapterEightOvertimeChoice.AUTHORIZE_BLANKET
            )
        )
        poorPlan = ObjectiveDeliveryGameRules.submitChapterEightTeamEvents(
            ObjectiveDeliveryGameRules.chooseChapterEightConflictResponse(
                ObjectiveDeliveryGameRules.chooseChapterEightPayrollResponse(
                    ObjectiveDeliveryGameRules.chooseChapterEightAbsenceResponse(
                        poorPlan, ObjectiveDeliveryChapterEightAbsenceResponse.PENALIZE_PROTECTED_ABSENCE
                    ),
                    ObjectiveDeliveryChapterEightPayrollResponse.TAKE_BACK_WITHOUT_REVIEW
                ),
                ObjectiveDeliveryChapterEightConflictResponse.RETALIATE
            )
        )
        poorPlan = ObjectiveDeliveryGameRules.submitChapterEightAnnualReview(
            ObjectiveDeliveryGameRules.setChapterEightRaiseAllocation(poorPlan, 3, 6)
        )
        assertEquals(ObjectiveDeliveryChapterEightOutcome.TEAM_PLAN_NEEDS_REVIEW, poorPlan.chapterEight.outcome)
        val retry = ObjectiveDeliveryGameRules.retryChapterEightPlan(poorPlan)
        assertEquals(ObjectiveDeliveryChapterEightState(), retry.chapterEight)
        assertEquals(8, retry.unlockedChapter)
    }

    @Test
    fun chapterNineRequiresSafeCooperationRefusesInfluenceAndReopensOnlyAfterVerification() {
        val unsafe = applyChapterNineChoices(
            startChapterNine(ObjectiveDeliveryCompanyModel.SERVICES),
            ObjectiveDeliveryChapterNineInspectionResponse.COOPERATE_AND_RECORD,
            ObjectiveDeliveryChapterNineSafetyResponse.KEEP_RUNNING,
            ObjectiveDeliveryChapterNineIntegrityResponse.ATTEMPT_TO_INFLUENCE,
            ObjectiveDeliveryChapterNineCorrectionChoice.CORRECT_AND_REQUEST_CHECK
        )
        assertEquals(ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD, unsafe.chapterNine.outcome)
        assertEquals(9, unsafe.unlockedChapter)
        assertEquals(unsafe, ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(unsafe)))

        val reopened = completeChapterNine(ObjectiveDeliveryGameRules.retryChapterNineCompliance(unsafe))
        assertEquals(ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION, reopened.chapterNine.outcome)
        assertEquals(10, reopened.unlockedChapter)
        assertTrue(ObjectiveDeliveryGameRules.evaluateChapterNine(reopened).reopeningAuthorized)

        val closed = applyChapterNineChoices(
            startChapterNine(ObjectiveDeliveryCompanyModel.WORKSHOP),
            ObjectiveDeliveryChapterNineInspectionResponse.COOPERATE_AND_RECORD,
            ObjectiveDeliveryChapterNineSafetyResponse.STOP_AFFECTED_WORK,
            ObjectiveDeliveryChapterNineIntegrityResponse.REFUSE_AND_REPORT,
            ObjectiveDeliveryChapterNineCorrectionChoice.MISS_DEADLINE
        )
        assertEquals(ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE, closed.chapterNine.outcome)
        assertEquals(9, closed.unlockedChapter)
    }

    @Test
    fun chapterTenProtectsThreeOrdersAndTheCashReserveToWinTheCampaign() {
        val successful = completeChapterTen(startChapterTen(ObjectiveDeliveryCompanyModel.DISTRIBUTION))
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterTen(successful)
        assertEquals(ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON, successful.chapterTen.outcome)
        assertTrue(evaluation.allOrdersProtected)
        assertTrue(evaluation.supplierOnTime)
        assertTrue(evaluation.pricingRespectsAgreements)
        assertTrue(evaluation.obligationsRemainCovered)
        assertEquals(13_000_000, evaluation.projectedCashCents)
        assertEquals(successful, ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(successful)
        ))

        val expressPlan = ObjectiveDeliveryGameRules.submitChapterTenPlan(
            ObjectiveDeliveryGameRules.chooseChapterTenCashPlan(
                ObjectiveDeliveryGameRules.chooseChapterTenPricing(
                    ObjectiveDeliveryGameRules.chooseChapterTenPriority(
                        ObjectiveDeliveryGameRules.chooseChapterTenSupplier(
                            startChapterTen(ObjectiveDeliveryCompanyModel.DISTRIBUTION),
                            ObjectiveDeliveryChapterTenSupplierChoice.EXPRESS_EVERYTHING
                        ),
                        ObjectiveDeliveryChapterTenPriorityChoice.PROTECT_COMMITMENTS
                    ),
                    ObjectiveDeliveryChapterTenPricingChoice.REVISE_NEW_QUOTES_ONLY
                ),
                ObjectiveDeliveryChapterTenCashChoice.PROTECT_OBLIGATIONS
            )
        )
        val expressEvaluation = ObjectiveDeliveryGameRules.evaluateChapterTen(expressPlan)
        assertEquals(ObjectiveDeliveryChapterTenOutcome.CASH_OR_CUSTOMER_RISK, expressPlan.chapterTen.outcome)
        assertFalse(expressEvaluation.obligationsRemainCovered)
        assertEquals(5_500_000, expressEvaluation.projectedCashCents)
    }

    @Test
    fun chapterEightToTenReplayAndSchemaMigrationsKeepCampaignProgress() {
        val chapterSeven = completeChapterSeven(startChapterSeven(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val v7 = JSONObject(ObjectiveDeliveryCampaignCodec.encode(chapterSeven)).apply {
            put("schemaVersion", 7)
            remove("chapterEightAttemptNumber"); remove("chapterEight")
            remove("chapterNineAttemptNumber"); remove("chapterNine")
            remove("chapterTenAttemptNumber"); remove("chapterTen")
        }
        val migratedV7 = ObjectiveDeliveryCampaignCodec.decode(v7.toString())
        assertEquals(8, migratedV7?.unlockedChapter)
        assertEquals(ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED, migratedV7?.chapterSeven?.outcome)

        val chapterEight = completeChapterEight(startChapterEight(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val v8 = JSONObject(ObjectiveDeliveryCampaignCodec.encode(chapterEight)).apply {
            put("schemaVersion", 8)
            remove("chapterNineAttemptNumber"); remove("chapterNine")
            remove("chapterTenAttemptNumber"); remove("chapterTen")
        }
        val migratedV8 = ObjectiveDeliveryCampaignCodec.decode(v8.toString())
        assertEquals(9, migratedV8?.unlockedChapter)
        assertEquals(ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS, migratedV8?.chapterEight?.outcome)

        val chapterNine = completeChapterNine(startChapterNine(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val v9 = JSONObject(ObjectiveDeliveryCampaignCodec.encode(chapterNine)).apply {
            put("schemaVersion", 9)
            remove("chapterTenAttemptNumber"); remove("chapterTen")
        }
        val migratedV9 = ObjectiveDeliveryCampaignCodec.decode(v9.toString())
        assertEquals(10, migratedV9?.unlockedChapter)
        assertEquals(ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION,
            migratedV9?.chapterNine?.outcome)

        val replay = ObjectiveDeliveryGameRules.replayChapterEight(chapterEight)
        val replayResult = completeChapterEight(requireNotNull(ObjectiveDeliveryCampaignCodec.decode(
            ObjectiveDeliveryCampaignCodec.encode(replay)
        )))
        assertEquals(9, replayResult.unlockedChapter)
        assertEquals(chapterEight.chapterEight, replayResult.chapterEight)
        val kept = ObjectiveDeliveryGameRules.keepChapterEightReplayResult(replayResult)
        assertEquals(9, kept.unlockedChapter)
        assertNull(kept.replaySession)
    }

    @Test
    fun replayingChapterTwoDoesNotOverwriteItsSavedResult() {
        val chapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(
            completeChapterOne(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        )
        val quote = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            chapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.BALANCED
        )
        val timed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(quote, ObjectiveDeliveryTimelineChoice.REQUESTED)
        val answered = ObjectiveDeliveryGameRules.chooseNegotiation(
            ObjectiveDeliveryGameRules.submitChapterTwoQuote(timed),
            ObjectiveDeliveryNegotiationChoice.EXPLAIN_VALUE
        )
        val result = ObjectiveDeliveryGameRules.submitChapterTwoNegotiation(answered)
        val replay = ObjectiveDeliveryGameRules.replayChapterTwo(result)

        assertEquals(result.chapterTwo, replay.chapterTwo)
        assertEquals(ObjectiveDeliveryChapterTwoPhase.QUOTE,
            ObjectiveDeliveryGameRules.chapterTwoState(replay).phase)
        assertEquals(replay, ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(replay)))
    }

    @Test
    fun versionedSaveRoundTripsAndUnknownSchemaIsRejected() {
        val state = ObjectiveDeliveryCampaign(
            companyModel = ObjectiveDeliveryCompanyModel.DISTRIBUTION,
            phase = ObjectiveDeliveryPhase.RESULT,
            unlockedChapter = 2,
            chapterOneWon = true,
            needQualified = true,
            priceChoice = ObjectiveDeliveryPriceChoice.ATTRACTIVE,
            timelineChoice = ObjectiveDeliveryTimelineChoice.FAST,
            outcome = ObjectiveDeliveryOutcome.ORDER_ACCEPTED,
            attemptNumber = 3,
            revision = 17,
            savedAtEpochMillis = 123456789L
        )

        assertEquals(state, ObjectiveDeliveryCampaignCodec.decode(ObjectiveDeliveryCampaignCodec.encode(state)))
        assertNull(ObjectiveDeliveryCampaignCodec.decode("{\"schemaVersion\":99}"))
    }

    @Test
    fun versionOneSaveMigratesWithoutLosingChapterOneProgress() {
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put("companyModel", "atelier")
            .put("phase", "RESULT")
            .put("unlockedChapter", 2)
            .put("chapterOneWon", true)
            .put("needQualified", true)
            .put("priceChoice", "BALANCED")
            .put("timelineChoice", "REQUESTED")
            .put("outcome", "ORDER_ACCEPTED")
            .put("attemptNumber", 4)
            .put("revision", 8)
            .put("savedAtEpochMillis", 123L)
            .put("cloudOwnerUid", JSONObject.NULL)
            .put("cloudHeadSnapshotId", JSONObject.NULL)
            .put("cloudParentSnapshotIds", org.json.JSONArray())

        val migrated = ObjectiveDeliveryCampaignCodec.decode(legacy.toString())

        assertEquals(ObjectiveDeliveryCompanyModel.WORKSHOP, migrated?.companyModel)
        assertEquals(ObjectiveDeliveryPhase.RESULT, migrated?.phase)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, migrated?.outcome)
        assertEquals(2, migrated?.unlockedChapter)
        assertEquals(1, migrated?.activeChapter)
        assertEquals(4, migrated?.attemptNumber)
    }

    @Test
    fun versionTwoAcceptedOrderMigratesAndUnlocksTheHandoffBoard() {
        val oldState = completeChapterTwo(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val versionTwo = JSONObject(ObjectiveDeliveryCampaignCodec.encode(oldState)).apply {
            put("schemaVersion", 2)
            put("unlockedChapter", 2)
            remove("chapterThree")
            remove("chapterThreeAttemptNumber")
            remove("chapterFour")
            remove("chapterFourAttemptNumber")
            remove("chapterFive")
            remove("chapterFiveAttemptNumber")
            remove("chapterSix")
            remove("chapterSixAttemptNumber")
            remove("chapterSeven")
            remove("chapterSevenAttemptNumber")
            remove("chapterEight")
            remove("chapterEightAttemptNumber")
            remove("chapterNine")
            remove("chapterNineAttemptNumber")
            remove("chapterTen")
            remove("chapterTenAttemptNumber")
        }

        val migrated = ObjectiveDeliveryCampaignCodec.decode(versionTwo.toString())

        assertEquals(3, migrated?.unlockedChapter)
        assertEquals(2, migrated?.activeChapter)
        assertEquals(ObjectiveDeliveryOutcome.ORDER_ACCEPTED, migrated?.chapterTwo?.outcome)
        assertEquals(ObjectiveDeliveryChapterThreeState(), migrated?.chapterThree)
    }

    @Test
    fun versionThreeReadyHandoffMigratesToTheTeamChapterWithoutLosingProgress() {
        val completedHandoff = completeChapterThree(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP))
        val versionThree = JSONObject(ObjectiveDeliveryCampaignCodec.encode(completedHandoff)).apply {
            put("schemaVersion", 3)
            put("unlockedChapter", 3)
            remove("chapterFour")
            remove("chapterFourAttemptNumber")
            remove("chapterFive")
            remove("chapterFiveAttemptNumber")
            remove("chapterSix")
            remove("chapterSixAttemptNumber")
            remove("chapterSeven")
            remove("chapterSevenAttemptNumber")
            remove("chapterEight")
            remove("chapterEightAttemptNumber")
            remove("chapterNine")
            remove("chapterNineAttemptNumber")
            remove("chapterTen")
            remove("chapterTenAttemptNumber")
        }

        val migrated = ObjectiveDeliveryCampaignCodec.decode(versionThree.toString())

        assertEquals(4, migrated?.unlockedChapter)
        assertEquals(ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH, migrated?.chapterThree?.outcome)
        assertEquals(ObjectiveDeliveryChapterFourState(), migrated?.chapterFour)
    }

    @Test
    fun versionFourTeamSaveMigratesToTheProcurementBoard() {
        val completeTeamPlan = completeChapterFour(ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.SERVICES))
        val versionFour = JSONObject(ObjectiveDeliveryCampaignCodec.encode(completeTeamPlan)).apply {
            put("schemaVersion", 4)
            remove("chapterFive")
            remove("chapterFiveAttemptNumber")
            remove("chapterSix")
            remove("chapterSixAttemptNumber")
            remove("chapterSeven")
            remove("chapterSevenAttemptNumber")
            remove("chapterEight")
            remove("chapterEightAttemptNumber")
            remove("chapterNine")
            remove("chapterNineAttemptNumber")
            remove("chapterTen")
            remove("chapterTenAttemptNumber")
        }

        val migrated = ObjectiveDeliveryCampaignCodec.decode(versionFour.toString())

        assertEquals(5, migrated?.unlockedChapter)
        assertEquals(4, migrated?.activeChapter)
        assertEquals(completeTeamPlan.chapterFour, migrated?.chapterFour)
        assertEquals(ObjectiveDeliveryChapterFiveState(), migrated?.chapterFive)
    }

    @Test
    fun saveDecoderRejectsUnknownEnumsAndUnexpectedFields() {
        val encoded = ObjectiveDeliveryCampaignCodec.encode(
            ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP)
        )
        val unknownPhase = JSONObject(encoded).put("phase", "UNRECOGNIZED").toString()
        val extraField = JSONObject(encoded).put("futureField", true).toString()

        assertNull(ObjectiveDeliveryCampaignCodec.decode(unknownPhase))
        assertNull(ObjectiveDeliveryCampaignCodec.decode(extraField))
    }

    @Test
    fun cloudGraphKeepsDivergentBranchesVisibleUntilTheyAreMerged() {
        val base = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP, revision = 1)
        val left = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP, revision = 2)
        val right = ObjectiveDeliveryCampaign(ObjectiveDeliveryCompanyModel.WORKSHOP, revision = 2)
        val branchA = ObjectiveDeliveryCloudSnapshot("a2", listOf("base"), left)
        val branchB = ObjectiveDeliveryCloudSnapshot("b2", listOf("base"), right)
        val branchBase = ObjectiveDeliveryCloudSnapshot("base", emptyList(), base)
        val divergent = listOf(branchBase, branchA, branchB)

        assertEquals(setOf("a2", "b2"), ObjectiveDeliveryCloudGraph.tips(divergent).map { it.id }.toSet())
        assertTrue(ObjectiveDeliveryCloudGraph.isAncestorOrSelf("base", "a2", divergent))
        assertFalse(ObjectiveDeliveryCloudGraph.isAncestorOrSelf("a2", "b2", divergent))

        val merged = ObjectiveDeliveryCloudSnapshot("merge", listOf("a2", "b2"), left.copy(revision = 3))
        assertEquals(listOf("merge"), ObjectiveDeliveryCloudGraph.tips(divergent + merged).map { it.id })
    }

    private fun completeChapterOne(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val qualified = ObjectiveDeliveryGameRules.qualifyNeed(start, askedQuestions = true)
        val priced = ObjectiveDeliveryGameRules.choosePrice(qualified, ObjectiveDeliveryPriceChoice.BALANCED)
        val timed = ObjectiveDeliveryGameRules.chooseTimeline(priced, ObjectiveDeliveryTimelineChoice.REQUESTED)
        return ObjectiveDeliveryGameRules.submitOffer(timed)
    }

    private fun completeChapterTwo(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val chapterTwo = ObjectiveDeliveryGameRules.continueToChapterTwo(completeChapterOne(start))
        val priced = ObjectiveDeliveryGameRules.chooseChapterTwoPrice(
            chapterTwo,
            ObjectiveDeliveryChapterTwoPriceChoice.BALANCED
        )
        val timed = ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(priced, ObjectiveDeliveryTimelineChoice.REQUESTED)
        val negotiation = ObjectiveDeliveryGameRules.submitChapterTwoQuote(timed)
        val answered = ObjectiveDeliveryGameRules.chooseNegotiation(
            negotiation,
            ObjectiveDeliveryNegotiationChoice.EXPLAIN_VALUE
        )
        return ObjectiveDeliveryGameRules.submitChapterTwoNegotiation(answered)
    }

    private fun completeChapterThree(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val chapterThree = ObjectiveDeliveryGameRules.continueToChapterThree(completeChapterTwo(start))
        val confirmed = ObjectiveDeliveryHandoffItem.values().fold(chapterThree) { state, item ->
            ObjectiveDeliveryGameRules.setHandoffItem(state, item, included = true)
        }
        return ObjectiveDeliveryGameRules.submitChapterThreeReview(confirmed)
    }

    private fun completeChapterFour(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.submitChapterFourPlan(
            planChapterFourWithApprovedLeave(
                ObjectiveDeliveryGameRules.continueToChapterFour(completeChapterThree(start))
            )
        )

    private fun startChapterFive(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterFive(completeChapterFour(ObjectiveDeliveryCampaign(model)))

    private fun completeChapterFive(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.submitChapterFivePlan(
            ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(
                ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(
                    startChapterFive(model), ObjectiveDeliveryChapterFiveSupplier.BACKUP
                ),
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
            )
        )

    private fun startChapterSix(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterSix(completeChapterFive(model))

    private fun startChapterSeven(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterSeven(completeChapterSix(startChapterSix(model)))

    private fun completeChapterSix(
        start: ObjectiveDeliveryCampaign,
        plan: ObjectiveDeliveryChapterSixProductionPlan = ObjectiveDeliveryChapterSixProductionPlan.PARALLEL_PREPARATION
    ): ObjectiveDeliveryCampaign {
        val planned = ObjectiveDeliveryGameRules.submitChapterSixPlan(
            ObjectiveDeliveryGameRules.chooseChapterSixProductionPlan(start, plan)
        )
        val inspected = ObjectiveDeliveryGameRules.submitChapterSixInspection(
            ObjectiveDeliveryGameRules.chooseChapterSixInspection(
                planned, ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST
            )
        )
        return ObjectiveDeliveryGameRules.submitChapterSixCorrection(
            ObjectiveDeliveryGameRules.chooseChapterSixCorrection(
                inspected, ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK
            )
        )
    }

    private fun completeChapterSeven(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val planned = ObjectiveDeliveryGameRules.submitChapterSevenDeliveryPlan(
            ObjectiveDeliveryGameRules.chooseChapterSevenDelivery(
                start, ObjectiveDeliveryChapterSevenDeliveryChoice.STANDARD
            )
        )
        val checked = ObjectiveDeliveryGameRules.submitChapterSevenOrderCheck(
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(
                planned, items = true, quantities = true, options = true, quality = true, documents = true
            )
        )
        return ObjectiveDeliveryGameRules.submitChapterSevenClaimAction(
            ObjectiveDeliveryGameRules.chooseChapterSevenClaimAction(
                checked, ObjectiveDeliveryChapterSevenClaimAction.INVESTIGATE_AND_FOLLOW_UP
            )
        )
    }

    private fun startChapterEight(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterEight(
            completeChapterSeven(startChapterSeven(model))
        )

    private fun completeChapterEight(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val plan = ObjectiveDeliveryGameRules.submitChapterEightWeekPlan(
            ObjectiveDeliveryGameRules.chooseChapterEightOvertime(
                ObjectiveDeliveryGameRules.chooseChapterEightBonusCriteria(
                    ObjectiveDeliveryGameRules.chooseChapterEightStaffing(
                        start, ObjectiveDeliveryChapterEightStaffing.STANDARD_TEN
                    ),
                    ObjectiveDeliveryChapterEightBonusCriteria.BALANCED
                ),
                ObjectiveDeliveryChapterEightOvertimeChoice.REPLAN_WITHIN_SCHEDULE
            )
        )
        val events = ObjectiveDeliveryGameRules.submitChapterEightTeamEvents(
            ObjectiveDeliveryGameRules.setChapterEightEventParticipants(
                ObjectiveDeliveryGameRules.chooseChapterEightConflictResponse(
                    ObjectiveDeliveryGameRules.chooseChapterEightPayrollResponse(
                        ObjectiveDeliveryGameRules.chooseChapterEightAbsenceResponse(
                            plan, ObjectiveDeliveryChapterEightAbsenceResponse.REASSIGN_QUALIFIED
                        ),
                        ObjectiveDeliveryChapterEightPayrollResponse.VERIFY_AND_CORRECT
                    ),
                    ObjectiveDeliveryChapterEightConflictResponse.LISTEN_AND_MEDIATE
                ),
                3
            )
        )
        var review = ObjectiveDeliveryGameRules.setChapterEightRaiseAllocation(events, 3, 2)
        if (start.companyModel.chapterEightProfile().profitShareEligible) {
            review = ObjectiveDeliveryGameRules.setChapterEightProfitShareConfirmed(review, true)
        }
        return ObjectiveDeliveryGameRules.submitChapterEightAnnualReview(review)
    }

    private fun startChapterNine(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterNine(
            completeChapterEight(startChapterEight(model))
        )

    private fun applyChapterNineChoices(
        start: ObjectiveDeliveryCampaign,
        inspection: ObjectiveDeliveryChapterNineInspectionResponse,
        safety: ObjectiveDeliveryChapterNineSafetyResponse,
        integrity: ObjectiveDeliveryChapterNineIntegrityResponse,
        correction: ObjectiveDeliveryChapterNineCorrectionChoice
    ): ObjectiveDeliveryCampaign {
        val safetyAction = ObjectiveDeliveryGameRules.submitChapterNineSafetyAction(
            ObjectiveDeliveryGameRules.chooseChapterNineIntegrityResponse(
                ObjectiveDeliveryGameRules.chooseChapterNineSafetyResponse(
                    ObjectiveDeliveryGameRules.submitChapterNineInspection(
                        ObjectiveDeliveryGameRules.chooseChapterNineInspectionResponse(start, inspection)
                    ),
                    safety
                ),
                integrity
            )
        )
        return ObjectiveDeliveryGameRules.submitChapterNineCorrection(
            ObjectiveDeliveryGameRules.chooseChapterNineCorrection(safetyAction, correction)
        )
    }

    private fun completeChapterNine(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign =
        applyChapterNineChoices(
            start,
            ObjectiveDeliveryChapterNineInspectionResponse.COOPERATE_AND_RECORD,
            ObjectiveDeliveryChapterNineSafetyResponse.STOP_AFFECTED_WORK,
            ObjectiveDeliveryChapterNineIntegrityResponse.REFUSE_AND_REPORT,
            ObjectiveDeliveryChapterNineCorrectionChoice.CORRECT_AND_REQUEST_CHECK
        )

    private fun startChapterTen(model: ObjectiveDeliveryCompanyModel): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.continueToChapterTen(completeChapterNine(startChapterNine(model)))

    private fun completeChapterTen(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign =
        ObjectiveDeliveryGameRules.submitChapterTenPlan(
            ObjectiveDeliveryGameRules.chooseChapterTenCashPlan(
                ObjectiveDeliveryGameRules.chooseChapterTenPricing(
                    ObjectiveDeliveryGameRules.chooseChapterTenPriority(
                        ObjectiveDeliveryGameRules.chooseChapterTenSupplier(
                            start, ObjectiveDeliveryChapterTenSupplierChoice.QUALIFIED_ALTERNATIVE
                        ),
                        ObjectiveDeliveryChapterTenPriorityChoice.PROTECT_COMMITMENTS
                    ),
                    ObjectiveDeliveryChapterTenPricingChoice.REVISE_NEW_QUOTES_ONLY
                ),
                ObjectiveDeliveryChapterTenCashChoice.PROTECT_OBLIGATIONS
            )
        )

    private fun planChapterFourWithApprovedLeave(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        var state = planChapterFourReview(start)
        state = ObjectiveDeliveryGameRules.assignChapterFourTask(
            state, ObjectiveDeliveryChapterFourTask.PRODUCTION, ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN
        )
        state = ObjectiveDeliveryGameRules.chooseChapterFourLeave(
            state, ObjectiveDeliveryChapterFourLeaveChoice.APPROVE_WITH_COVER
        )
        state = ObjectiveDeliveryGameRules.chooseChapterFourHire(
            state, ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE
        )
        state = ObjectiveDeliveryGameRules.setChapterFourHireTerms(
            state, ObjectiveDeliveryChapterFourContract.CDD,
            ObjectiveDeliveryChapterFourClassification.NON_CADRE
        )
        return state
    }

    private fun planChapterFourReview(start: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        var state = ObjectiveDeliveryGameRules.assignChapterFourTask(
            start, ObjectiveDeliveryChapterFourTask.CUSTOMER_FILE, ObjectiveDeliveryTeamMember.ELISE
        )
        state = ObjectiveDeliveryGameRules.assignChapterFourTask(
            state, ObjectiveDeliveryChapterFourTask.PRODUCTION, ObjectiveDeliveryTeamMember.KARIM
        )
        state = ObjectiveDeliveryGameRules.assignChapterFourTask(
            state, ObjectiveDeliveryChapterFourTask.DISPATCH, ObjectiveDeliveryTeamMember.NOAH
        )
        state = ObjectiveDeliveryGameRules.chooseChapterFourLeave(
            state, ObjectiveDeliveryChapterFourLeaveChoice.OFFER_ALTERNATIVE_DATE
        )
        state = ObjectiveDeliveryGameRules.chooseChapterFourHire(
            state, ObjectiveDeliveryChapterFourHireDecision.DECLINE_WITH_ALTERNATIVE
        )
        state = ObjectiveDeliveryGameRules.recordChapterFourAnnualReview(
            state, ObjectiveDeliveryTeamMember.KARIM, factsDiscussed = true
        )
        state = ObjectiveDeliveryGameRules.setChapterFourRaiseEnvelope(state, 3)
        return ObjectiveDeliveryGameRules.setChapterFourIndividualRaise(state, 2)
    }
}
