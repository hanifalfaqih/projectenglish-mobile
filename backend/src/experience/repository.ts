import type { PrismaClient } from "@prisma/client";
import type {
  CreateExperienceProfileInput,
  ExperienceProfile,
  ExperienceItem,
} from "./schema.js";

/**
 * Thin data-access for Experience Profiles (Actual M9).
 *
 * Mirrors the existing ConversationRepository style: a small class over the
 * Prisma client, returning plain domain records. Backend owns ids and the
 * deterministic `position` ordering of items.
 */
export class ExperienceProfileRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async create(
    input: CreateExperienceProfileInput,
  ): Promise<ExperienceProfile> {
    const row = await this.prisma.experienceProfile.create({
      data: {
        items: {
          create: input.items.map((item, index) => ({
            title: item.title,
            organization: item.organization ?? null,
            role: item.role ?? null,
            description: item.description,
            skills: item.skills,
            position: index, // deterministic, backend-owned
          })),
        },
      },
      include: {
        items: { orderBy: { position: "asc" } },
      },
    });

    return this.toProfile(row);
  }

  async findById(id: string): Promise<ExperienceProfile | null> {
    const row = await this.prisma.experienceProfile.findUnique({
      where: { id },
      include: {
        items: { orderBy: { position: "asc" } },
      },
    });
    if (!row) return null;
    return this.toProfile(row);
  }

  private toProfile(row: {
    id: string;
    createdAt: Date;
    updatedAt: Date;
    items: Array<{
      id: string;
      title: string;
      organization: string | null;
      role: string | null;
      description: string;
      skills: string[];
      position: number;
    }>;
  }): ExperienceProfile {
    const items: ExperienceItem[] = row.items
      .slice()
      .sort((a, b) => a.position - b.position)
      .map((i) => ({
        id: i.id,
        title: i.title,
        organization: i.organization,
        role: i.role,
        description: i.description,
        skills: i.skills,
        position: i.position,
      }));

    return {
      id: row.id,
      items,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
