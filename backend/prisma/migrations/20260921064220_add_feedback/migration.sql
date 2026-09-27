-- CreateTable
CREATE TABLE "Feedback" (
    "id" TEXT NOT NULL,
    "conversationId" TEXT NOT NULL,
    "promptVersion" TEXT NOT NULL,
    "overall" TEXT NOT NULL,
    "items" JSONB NOT NULL,
    "professionalCommunication" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Feedback_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "Feedback_conversationId_key" ON "Feedback"("conversationId");

-- AddForeignKey
ALTER TABLE "Feedback" ADD CONSTRAINT "Feedback_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "Conversation"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
