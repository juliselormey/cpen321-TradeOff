-- CreateTable
CREATE TABLE `Position` (
    `id` VARCHAR(191) NOT NULL,
    `userId` VARCHAR(191) NOT NULL,
    `ticker` VARCHAR(191) NOT NULL,
    `quantity` DECIMAL(18, 4) NOT NULL,
    `averageCost` DECIMAL(18, 4) NOT NULL,
    `createdAt` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updatedAt` DATETIME(3) NOT NULL,

    INDEX `Position_userId_idx`(`userId`),
    UNIQUE INDEX `Position_userId_ticker_key`(`userId`, `ticker`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- CreateTable
CREATE TABLE `Trade` (
    `id` VARCHAR(191) NOT NULL,
    `userId` VARCHAR(191) NOT NULL,
    `ticker` VARCHAR(191) NOT NULL,
    `type` ENUM('BUY', 'SELL') NOT NULL,
    `quantity` DECIMAL(18, 4) NOT NULL,
    `pricePerShare` DECIMAL(18, 4) NOT NULL,
    `totalAmount` DECIMAL(18, 4) NOT NULL,
    `createdAt` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    INDEX `Trade_userId_idx`(`userId`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- CreateTable
CREATE TABLE `Order` (
    `id` VARCHAR(191) NOT NULL,
    `userId` VARCHAR(191) NOT NULL,
    `ticker` VARCHAR(191) NOT NULL,
    `side` ENUM('BUY', 'SELL') NOT NULL,
    `type` ENUM('MARKET', 'LIMIT') NOT NULL,
    `status` ENUM('PENDING', 'FILLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    `quantity` DECIMAL(18, 4) NOT NULL,
    `limitPrice` DECIMAL(18, 4) NULL,
    `executedPrice` DECIMAL(18, 4) NULL,
    `createdAt` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updatedAt` DATETIME(3) NOT NULL,

    INDEX `Order_userId_idx`(`userId`),
    INDEX `Order_status_ticker_idx`(`status`, `ticker`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- AddForeignKey
ALTER TABLE `Position` ADD CONSTRAINT `Position_userId_fkey` FOREIGN KEY (`userId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `Trade` ADD CONSTRAINT `Trade_userId_fkey` FOREIGN KEY (`userId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `Order` ADD CONSTRAINT `Order_userId_fkey` FOREIGN KEY (`userId`) REFERENCES `User`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
