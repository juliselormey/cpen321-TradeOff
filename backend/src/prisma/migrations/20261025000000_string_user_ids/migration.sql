-- Align the original integer IDs with the current String/uuid Prisma schema.
-- Existing IDs are preserved as strings, including their account/token links.
ALTER TABLE `Account` DROP FOREIGN KEY `Account_userId_fkey`;
ALTER TABLE `RefreshToken` DROP FOREIGN KEY `RefreshToken_userId_fkey`;

-- Remove AUTO_INCREMENT before converting the primary key to a string.
ALTER TABLE `User` MODIFY `id` INTEGER NOT NULL;
ALTER TABLE `User` MODIFY `id` VARCHAR(191) NOT NULL;
ALTER TABLE `Account` MODIFY `userId` VARCHAR(191) NOT NULL;
ALTER TABLE `RefreshToken` MODIFY `userId` VARCHAR(191) NOT NULL;

ALTER TABLE `Account` ADD CONSTRAINT `Account_userId_fkey`
  FOREIGN KEY (`userId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE `RefreshToken` ADD CONSTRAINT `RefreshToken_userId_fkey`
  FOREIGN KEY (`userId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
