CREATE TABLE `StockFavorite` (
    `userId` VARCHAR(191) NOT NULL,
    `ticker` VARCHAR(16) NOT NULL,
    PRIMARY KEY (`userId`, `ticker`),
    CONSTRAINT `StockFavorite_userId_fkey` FOREIGN KEY (`userId`) REFERENCES `User` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
