package com.infinityisland.repositories;

import com.infinityisland.dao.DailySummary;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailySummaryRepository extends MongoRepository<DailySummary, String> {

    Optional<DailySummary> findByUserIdAndDate(String userId, LocalDate date);

    List<DailySummary> findByUserId(String userId);

    List<DailySummary> findByDate(LocalDate date);

    List<DailySummary> findAllByUserIdAndDateBetweenOrderByDateDesc(String userId, LocalDate from, LocalDate to);

    @Query(value = "{ 'userId': ?0 }", fields = "{ 'correctCount': 1 }")
    List<DailySummary> findAllCountsByUser(String userId);

    default long sumCorrectForUser(String userId) {
        return findAllCountsByUser(userId).stream()
                .mapToLong(DailySummary::getCorrectCount)
                .sum();
    }

    @Query(value = "{ 'userId': ?0, 'date': { $ne: ?1 } }", fields = "{ 'totalActiveMs': 1 }")
    List<DailySummary> findAllTimesExcludingDate(String userId, LocalDate date);

    default long sumTotalActiveMsExcludingDate(String userId, LocalDate date) {
        return findAllTimesExcludingDate(userId, date).stream()
                .mapToLong(DailySummary::getTotalActiveMs)
                .sum();
    }
    void deleteByUserId(String userId);
    List<DailySummary> findByUserIdInAndDate(List<String> userIds, LocalDate date);
    List<DailySummary> findByUserIdIn(List<String> userIds);
}