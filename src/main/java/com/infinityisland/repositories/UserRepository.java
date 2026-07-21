package com.infinityisland.repositories;

import com.infinityisland.dao.user.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByPin(String pin);
    // Handy when you only need the id (auth flow)
    @Query(value = "{ 'pin': ?0 }", fields = "{ '_id': 1 }")
    Optional<User> findIdByPin(String pin);

}
