package com.infinityisland.repositories;

import com.infinityisland.dao.VideoRating;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface VideoRatingRepository extends MongoRepository<VideoRating, String> {
    void deleteByUserId(String userId);

}


