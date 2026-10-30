-- A failed foreign-key addition may have already created this table in MySQL.
CREATE TABLE IF NOT EXISTS `Friendship` (
    `id` VARCHAR(191) NOT NULL,
    `pairKey` VARCHAR(73) NOT NULL,
    `senderId` VARCHAR(191) NOT NULL,
    `recipientId` VARCHAR(191) NOT NULL,
    `status` ENUM('PENDING', 'ACCEPTED') NOT NULL DEFAULT 'PENDING',
    `createdAt` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE INDEX `Friendship_pairKey_key`(`pairKey`),
    INDEX `Friendship_senderId_status_idx`(`senderId`, `status`),
    INDEX `Friendship_recipientId_status_idx`(`recipientId`, `status`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `Friendship` ADD CONSTRAINT `Friendship_senderId_fkey`
  FOREIGN KEY (`senderId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE `Friendship` ADD CONSTRAINT `Friendship_recipientId_fkey`
  FOREIGN KEY (`recipientId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
