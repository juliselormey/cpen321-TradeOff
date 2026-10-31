ALTER TABLE `User` ADD COLUMN `username` VARCHAR(24) NULL;
CREATE UNIQUE INDEX `User_username_key` ON `User`(`username`);
